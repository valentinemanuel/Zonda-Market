package com.zondamarket.identity.service;

import com.zondamarket.identity.domain.EmailPurpose;
import com.zondamarket.identity.domain.UserAccount;
import com.zondamarket.identity.repository.IdentityEmailStore;
import com.zondamarket.identity.repository.RefreshSessionRepository;
import com.zondamarket.identity.repository.UserAccountRepository;
import com.zondamarket.shared.errors.ApiProblemException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityEmailService {
    private final IdentityEmailStore store;
    private final UserAccountRepository accounts;
    private final RefreshSessionRepository sessions;
    private final EmailLinkTokens tokens;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final URI publicOrigin;

    IdentityEmailService(IdentityEmailStore store, UserAccountRepository accounts, RefreshSessionRepository sessions,
                         EmailLinkTokens tokens, PasswordEncoder passwords, Clock clock,
                         @Value("${zonda.mail.public-origin}") URI publicOrigin) {
        if (publicOrigin.getHost() == null || publicOrigin.getRawUserInfo() != null
                || publicOrigin.getRawQuery() != null || publicOrigin.getRawFragment() != null
                || !publicOrigin.getPath().matches("/?")
                || !("https".equals(publicOrigin.getScheme()) || ("http".equals(publicOrigin.getScheme())
                && ("localhost".equals(publicOrigin.getHost()) || "127.0.0.1".equals(publicOrigin.getHost()))))) {
            throw new IllegalArgumentException("MAIL_PUBLIC_ORIGIN debe ser un origen HTTPS o local.");
        }
        this.store = store;
        this.accounts = accounts;
        this.sessions = sessions;
        this.tokens = tokens;
        this.passwords = passwords;
        this.clock = clock;
        this.publicOrigin = publicOrigin;
    }

    @Transactional
    public void request(EmailPurpose purpose, String email, String remoteAddress) {
        Instant now = clock.instant();
        Instant window = now.truncatedTo(ChronoUnit.HOURS);
        if (!store.take("request-ip:" + SessionTokens.hash(remoteAddress), window, 30)
                || !store.take("request-account:" + SessionTokens.hash(email), window, 5)) {
            return;
        }
        accounts.lockByEmailAndDeletedFalse(email).filter(account -> eligible(account, purpose)).ifPresent(account -> {
            UUID id = UUID.randomUUID();
            String hash = SessionTokens.hash(tokens.forEvent(id, purpose));
            store.enqueue(id, account.getId(), purpose, hash, account.getEmail(),
                    publicOrigin.resolve(purpose.path()).toString(), clock.instant());
        });
    }

    @Transactional
    public boolean allowConfirmation(String remoteAddress) {
        return store.take("confirm-ip:" + SessionTokens.hash(remoteAddress),
                clock.instant().truncatedTo(ChronoUnit.HOURS), 60);
    }

    @Transactional
    public void confirm(EmailPurpose purpose, String token, String newPassword) {
        if (newPassword != null) {
            AuthService.requirePasswordLength(newPassword);
        }
        String hash = SessionTokens.hash(token);
        UUID id = store.accountFor(purpose, hash, clock.instant()).orElseThrow(IdentityEmailService::invalidLink);
        var account = accounts.lockByIdAndDeletedFalse(id).filter(value -> eligible(value, purpose))
                .orElseThrow(IdentityEmailService::invalidLink);
        Instant now = clock.instant();
        if (!store.consume(id, purpose, hash, now)) {
            throw invalidLink();
        }
        if (purpose == EmailPurpose.VERIFY_EMAIL) {
            account.verifyEmail();
        } else {
            account.changePassword(passwords.encode(newPassword));
            sessions.revokeAllForAccount(id, now);
        }
        store.invalidateOthers(id, purpose, now);
    }

    private static boolean eligible(UserAccount account, EmailPurpose purpose) {
        return purpose == EmailPurpose.VERIFY_EMAIL ? !account.isEmailVerified() : account.getPasswordHash() != null;
    }

    private static ApiProblemException invalidLink() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_LINK",
                "Este enlace venció o ya no está disponible. Pedí uno nuevo.");
    }
}
