package com.zondamarket.identity.service;

import java.net.URI;
import java.util.Optional;

public interface GoogleOidcClient {
    record Identity(String issuer, String subject, String email) {}

    boolean enabled();

    URI redirectUri();

    URI authorizationUri(String state, String nonce, String verifier);

    Optional<Identity> authenticate(String code, String state, String nonce, String verifier);
}
