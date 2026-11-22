package com.zondamarket.checkout.service;

import com.zondamarket.checkout.dto.PaymentWebhookTopic;
import com.zondamarket.checkout.repository.PaymentWebhookInbox;
import com.zondamarket.shared.errors.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentWebhookService {
    private final PaymentWebhookInbox inbox;
    private final Clock clock;
    private final String cardSecret;
    private final String proSecret;

    PaymentWebhookService(PaymentWebhookInbox inbox, Clock clock,
                          @Value("${zonda.mercado-pago.webhooks.card-secret}") String cardSecret,
                          @Value("${zonda.mercado-pago.webhooks.pro-secret}") String proSecret) {
        this.inbox = inbox;
        this.clock = clock;
        this.cardSecret = cardSecret;
        this.proSecret = proSecret;
    }

    @Transactional(timeout = 5)
    public void receive(PaymentWebhookTopic topic, String resource, String request, String signature, String event, UUID trace) {
        String secret = topic == PaymentWebhookTopic.ORDER ? cardSecret : proSecret;
        if (secret.isBlank()) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_CONFIGURED", "El webhook no está configurado.");
        }
        if (request == null || !request.matches("[A-Za-z0-9._:-]{1,128}")
                || !authentic(signature, resource, request, secret)) {
            throw new ApiProblemException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", "La firma no es válida.");
        }
        inbox.receive(topic, event, resource, request, trace, clock.instant());
    }

    private static boolean authentic(String signature, String resource, String request, String secret) {
        if (signature == null || signature.length() > 256) return false;
        String timestamp = null;
        String digest = null;
        for (String item : signature.split(",", -1)) {
            String[] pair = item.strip().split("=", 2);
            if (pair.length != 2) return false;
            if ("ts".equals(pair[0]) && timestamp == null) timestamp = pair[1];
            else if ("v1".equals(pair[0]) && digest == null) digest = pair[1];
            else return false;
        }
        if (timestamp == null || !timestamp.matches("(?:[0-9]{10}|[0-9]{13})")
                || digest == null || !digest.matches("[0-9a-fA-F]{64}")) return false;
        // The signature covers this manifest. Provider retries can arrive days after the initial delivery.
        String manifest = "id:" + resource + ";request-id:" + request + ";ts:" + timestamp + ";";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)),
                    HexFormat.of().parseHex(digest));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("No se pudo inicializar HmacSHA256.", failure);
        }
    }
}
