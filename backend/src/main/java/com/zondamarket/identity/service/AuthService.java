package com.zondamarket.identity.service;

import com.zondamarket.identity.domain.RefreshSession;
import com.zondamarket.identity.domain.UserAccount;
import com.zondamarket.identity.dto.AccessTokenResponse;
import com.zondamarket.identity.dto.AccountResponse;
import com.zondamarket.identity.dto.LoginRequest;
import com.zondamarket.identity.dto.RegisterRequest;
import com.zondamarket.identity.repository.RefreshSessionRepository;
import com.zondamarket.identity.repository.UserAccountRepository;
import com.zondamarket.shared.errors.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    public record IssuedSession(AccessTokenResponse access, String refresh, Duration cookieAge) {}

    private final UserAccountRepository accounts;
    private final RefreshSessionRepository sessions;
    private final SessionTokens tokens;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final String fallbackHash;

    AuthService(UserAccountRepository accounts, RefreshSessionRepository sessions, SessionTokens tokens,
                PasswordEncoder passwords, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.tokens = tokens;
        this.passwords = passwords;
        this.clock = clock;
        fallbackHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public IssuedSession register(RegisterRequest request, String previousRefresh) {
        requirePasswordLength(request.password());
        UserAccount account = new UserAccount(request.email(), passwords.encode(request.password()));
        try {
            accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && "uq_user_account_email".equals(violation.getConstraintName())) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "EMAIL_IN_USE", "El correo ya está registrado.");
            }
            throw exception;
        }
        return replaceFamily(account, previousRefresh);
    }

    @Transactional
    public IssuedSession login(LoginRequest request, String previousRefresh) {
        // Serialize password-based session issuance with password changes.
        var account = accounts.lockByEmailAndDeletedFalse(request.email());
        verifyPassword(account, request.password());
        return replaceFamily(account.orElseThrow(), previousRefresh);
    }

    void verifyPassword(Optional<UserAccount> account, String password) {
        var hash = account.map(UserAccount::getPasswordHash);
        boolean matches = !passwordTooLong(password) && passwords.matches(password, hash.orElse(fallbackHash));
        if (!matches || hash.isEmpty()) {
            throw new ApiProblemException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "El correo o la contraseña son incorrectos.");
        }
    }

    IssuedSession replaceFamily(UserAccount account, String previousRefresh) {
        revoke(previousRefresh);
        return startFamily(account);
    }

    RefreshSession requireBrowserFamily(UUID accountId, String refresh) {
        var claims = refreshClaims(refresh).filter(value -> accountId.equals(value.accountId()))
                .orElseThrow(AuthService::unauthorized);
        return sessions.lockFamily(claims.familyId(), accountId)
                .filter(family -> family.isActive(clock.instant()) && family.getSessionVersion() == claims.version()
                        && family.matches(SessionTokens.hash(claims.jti()), claims.generation()))
                .orElseThrow(AuthService::unauthorized);
    }

    @Transactional
    public Optional<IssuedSession> refresh(String refreshToken) {
        return refreshClaims(refreshToken).flatMap(claims -> {
            var family = sessions.lockFamily(claims.familyId(), claims.accountId()).orElse(null);
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            if (family == null || !family.isActive(now) || family.getSessionVersion() != claims.version()) {
                return Optional.empty();
            }
            if (!family.matches(SessionTokens.hash(claims.jti()), claims.generation())) {
                // Return normally so the replay revocation commits with the lock held.
                family.revoke(now);
                return Optional.empty();
            }
            String jti = UUID.randomUUID().toString();
            family.rotate(SessionTokens.hash(jti));
            return Optional.of(issue(family, now, jti));
        });
    }

    @Transactional
    public void logout(String refreshToken) {
        revoke(refreshToken);
    }

    @Transactional(readOnly = true)
    public AccountResponse currentAccount(Jwt bearer) {
        var claims = tokens.access(bearer);
        return sessions.findActiveAccount(claims.familyId(), claims.accountId(), claims.version(), clock.instant())
                .map(AccountResponse::from)
                .orElseThrow(() -> new BadCredentialsException("La sesión no es válida."));
    }

    private void revoke(String refreshToken) {
        refreshClaims(refreshToken).ifPresent(claims -> {
            Instant now = clock.instant();
            sessions.lockFamily(claims.familyId(), claims.accountId())
                    .filter(family -> family.getSessionVersion() == claims.version() && family.isActive(now))
                    .ifPresent(family -> family.revoke(now));
        });
    }

    private Optional<SessionTokens.Claims> refreshClaims(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(tokens.refresh(token));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private IssuedSession startFamily(UserAccount account) {
        String jti = UUID.randomUUID().toString();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var family = sessions.save(new RefreshSession(account, SessionTokens.hash(jti),
                now.plus(SessionTokens.REFRESH_TTL)));
        return issue(family, now, jti);
    }

    private IssuedSession issue(RefreshSession family, Instant now, String jti) {
        Duration remaining = Duration.between(now, family.getExpiresAt());
        long accessSeconds = Math.min(SessionTokens.ACCESS_TTL.toSeconds(), remaining.toSeconds());
        return new IssuedSession(new AccessTokenResponse(tokens.issueAccess(family, now), accessSeconds,
                List.of(family.getAccount().getRole())), tokens.issueRefresh(family, now, jti), remaining);
    }

    public static ApiProblemException unauthorized() {
        return new ApiProblemException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "La sesión no es válida.");
    }

    static void requirePasswordLength(String password) {
        if (passwordTooLong(password)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "La contraseña admite hasta 72 bytes UTF-8.");
        }
    }

    private static boolean passwordTooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > 72;
    }
}
