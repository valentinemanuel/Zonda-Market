package com.zondamarket.identity.service;

import com.zondamarket.identity.repository.GoogleIdentityStore;
import com.zondamarket.identity.repository.GoogleIdentityStore.Attempt;
import com.zondamarket.identity.repository.GoogleIdentityStore.Grant;
import com.zondamarket.identity.repository.GoogleIdentityStore.Target;
import com.zondamarket.shared.errors.ApiProblemException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class GoogleAuthFlow {
    public static final Duration ATTEMPT_AGE = Duration.ofMinutes(5);
    public static final Duration EXCHANGE_AGE = Duration.ofMinutes(1);
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Start(URI url, String browser) {}
    public record Callback(String code, String error) {}

    private final GoogleOidcClient provider;
    private final GoogleIdentityStore store;
    private final GoogleAccounts accounts;
    private final Clock clock;

    GoogleAuthFlow(GoogleOidcClient provider, GoogleIdentityStore store, GoogleAccounts accounts, Clock clock) {
        this.provider = provider;
        this.store = store;
        this.accounts = accounts;
        this.clock = clock;
    }

    public boolean enabled(String host) {
        return provider.enabled() && provider.redirectUri().getAuthority().equalsIgnoreCase(host);
    }

    public Start startLogin(String host) {
        requireProvider(host);
        return begin(new Target(null, null));
    }

    public Start startLink(String host, UUID account, String password, String refresh) {
        requireProvider(host);
        return begin(accounts.authorizeLink(account, password, refresh));
    }

    public Callback callback(String host, String state, String browser, String code, String error) {
        if (!enabled(host) || !opaque(state) || !opaque(browser)) {
            return new Callback(null, "invalid");
        }
        var pending = store.takeAttempt(hash(state), hash(browser), clock.instant());
        if (pending.isEmpty()) {
            return new Callback(null, "invalid");
        }
        if (error != null || code == null || code.isBlank() || code.length() > 4096) {
            return new Callback(null, "access_denied".equals(error) ? "denied" : "invalid");
        }
        var attempt = pending.orElseThrow();
        var identity = provider.authenticate(code, state, attempt.nonce(), attempt.verifier());
        if (identity.isEmpty()) {
            return new Callback(null, "invalid");
        }
        String exchange = random();
        store.addGrant(hash(exchange), hash(browser), new Grant(identity.orElseThrow(), attempt.target()),
                clock.instant().plus(EXCHANGE_AGE));
        return new Callback(exchange, null);
    }

    public AuthService.IssuedSession exchange(String host, String code, String browser, String refresh) {
        requireProvider(host);
        if (!opaque(code) || !opaque(browser)) {
            throw invalidExchange();
        }
        var grant = store.takeGrant(hash(code), hash(browser), clock.instant()).orElseThrow(GoogleAuthFlow::invalidExchange);
        return accounts.exchange(grant, refresh);
    }

    private Start begin(Target target) {
        String browser = random();
        String state = random();
        var attempt = new Attempt(random(), random(), target);
        var now = clock.instant();
        store.deleteExpired(now);
        store.addAttempt(hash(state), hash(browser), attempt, now.plus(ATTEMPT_AGE));
        return new Start(provider.authorizationUri(state, attempt.nonce(), attempt.verifier()), browser);
    }

    private void requireProvider(String host) {
        if (!enabled(host)) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_UNAVAILABLE",
                    "El acceso con Google no está habilitado en este origen.");
        }
    }

    private static ApiProblemException invalidExchange() {
        return new ApiProblemException(HttpStatus.UNAUTHORIZED, "GOOGLE_EXCHANGE_INVALID",
                "El código de acceso con Google venció o ya se usó.");
    }

    private static boolean opaque(String value) {
        return value != null && OPAQUE.matcher(value).matches();
    }

    private static String hash(String value) {
        return SessionTokens.hash(value);
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
