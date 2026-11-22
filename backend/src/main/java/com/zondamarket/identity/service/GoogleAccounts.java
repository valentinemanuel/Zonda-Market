package com.zondamarket.identity.service;

import com.zondamarket.identity.domain.UserAccount;
import com.zondamarket.identity.repository.GoogleIdentityStore;
import com.zondamarket.identity.repository.GoogleIdentityStore.Grant;
import com.zondamarket.identity.repository.GoogleIdentityStore.Target;
import com.zondamarket.identity.repository.UserAccountRepository;
import com.zondamarket.shared.errors.ApiProblemException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoogleAccounts {
    private final GoogleIdentityStore identities;
    private final UserAccountRepository accounts;
    private final AuthService auth;
    private final Clock clock;

    GoogleAccounts(GoogleIdentityStore identities, UserAccountRepository accounts, AuthService auth, Clock clock) {
        this.identities = identities;
        this.accounts = accounts;
        this.auth = auth;
        this.clock = clock;
    }

    @Transactional
    public Target authorizeLink(UUID accountId, String password, String refresh) {
        var account = activeAccount(accountId);
        auth.verifyPassword(Optional.of(account), password);
        var family = auth.requireBrowserFamily(accountId, refresh);
        return new Target(accountId, family.getId());
    }

    @Transactional(readOnly = true)
    public boolean isLinked(UUID account) {
        return identities.hasGoogle(account);
    }

    @Transactional
    public AuthService.IssuedSession exchange(Grant grant, String refresh) {
        var accountId = grant.target().account();
        var account = activeAccount(accountId == null ? signIn(grant.identity()) : accountId);
        if (accountId != null) {
            link(grant, refresh);
        }
        return auth.replaceFamily(account, refresh);
    }

    private UserAccount activeAccount(UUID accountId) {
        return accounts.lockByIdAndDeletedFalse(accountId).filter(account -> !account.isSuspended())
                .orElseThrow(AuthService::unauthorized);
    }

    private UUID signIn(GoogleOidcClient.Identity identity) {
        var existing = identities.findAccount(identity);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        var now = clock.instant();
        var created = identities.addGoogleAccount(identity.email(), identity.email().endsWith("@gmail.com"), now);
        if (created.isEmpty()) {
            // A concurrent callback for the same Google identity may have just committed.
            return identities.findAccount(identity).orElseThrow(() -> new ApiProblemException(HttpStatus.CONFLICT,
                    "GOOGLE_LINK_REQUIRED", "Ingresá con tu contraseña y vinculá Google desde Seguridad de cuenta."));
        }
        UUID account = created.orElseThrow();
        if (!identities.addIdentity(account, identity, now)) {
            throw conflict();
        }
        return account;
    }

    private void link(Grant grant, String refresh) {
        UUID accountId = grant.target().account();
        var family = auth.requireBrowserFamily(accountId, refresh);
        if (!family.getId().equals(grant.target().session())) {
            throw AuthService.unauthorized();
        }
        if (!family.getAccount().getEmail().equals(grant.identity().email())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "GOOGLE_EMAIL_MISMATCH",
                    "Elegí en Google el mismo correo de tu cuenta de Zonda.");
        }
        if (!identities.addIdentity(accountId, grant.identity(), clock.instant())
                && identities.findAccount(grant.identity()).filter(accountId::equals).isEmpty()) {
            throw conflict();
        }
    }

    private static ApiProblemException conflict() {
        return new ApiProblemException(HttpStatus.CONFLICT, "GOOGLE_IDENTITY_CONFLICT",
                "Ya existe una vinculación para esta cuenta o identidad de Google.");
    }
}
