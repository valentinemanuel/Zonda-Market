package com.zondamarket.identity.service;

import com.zondamarket.identity.domain.EmailPurpose;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class EmailLinkTokens {
    private final SecretKeySpec key;

    EmailLinkTokens(@Value("${zonda.auth.jwt-key}") String secret) {
        key = new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
    }

    // Rebuild the same token for each delivery attempt without persisting its plaintext.
    String forEvent(UUID event, EmailPurpose purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            String input = "zonda-email-link:v1:" + purpose.name() + ":" + event;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
