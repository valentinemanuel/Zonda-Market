package com.zondamarket.checkout.controller;

import static com.zondamarket.shared.web.CorrelationIdFilter.responseTraceId;

import com.zondamarket.checkout.dto.PaymentWebhookTopic;
import com.zondamarket.checkout.service.PaymentWebhookService;
import com.zondamarket.shared.errors.ApiProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
class PaymentWebhookController {
    private static final int MAX_BODY = 16 * 1024;
    private final PaymentWebhookService webhooks;
    private final ObjectMapper json;

    PaymentWebhookController(PaymentWebhookService webhooks, ObjectMapper json) {
        this.webhooks = webhooks;
        this.json = json;
    }

    @PostMapping(path = "/api/v1/webhooks/mercadopago", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> receive(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String topicName = single(request.getParameterValues("type"));
        var topic = switch (topicName == null ? "" : topicName) {
            case "order" -> PaymentWebhookTopic.ORDER;
            case "payment" -> PaymentWebhookTopic.PAYMENT;
            default -> throw invalid();
        };
        String resource = single(request.getParameterValues("data.id"));
        if (!topic.accepts(resource)) throw invalid();
        if (request.getContentLengthLong() > MAX_BODY) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw tooLarge();

        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (JacksonException malformed) {
            throw invalid();
        }
        if (event == null || !event.isObject() || !topic.webhookName().equals(value(event.path("type")))
                || !resource.equalsIgnoreCase(value(event.path("data").path("id")))) throw invalid();
        String eventId = value(event.path("id"));
        if (!eventId.matches("[A-Za-z0-9_-]{1,80}")) throw invalid();
        webhooks.receive(topic, resource, single(Collections.list(request.getHeaders("x-request-id"))),
                single(Collections.list(request.getHeaders("x-signature"))), eventId,
                responseTraceId(response));
        return ResponseEntity.ok().build();
    }

    private static String single(String[] values) {
        return values != null && values.length == 1 ? values[0] : null;
    }

    private static String single(List<String> values) {
        return values.size() == 1 ? values.getFirst() : null;
    }

    private static String value(JsonNode field) {
        return field.isString() || field.isIntegralNumber() ? field.asString() : "";
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "WEBHOOK_INVALID", "La notificación no tiene el formato esperado.");
    }

    private static ApiProblemException tooLarge() {
        return new ApiProblemException(HttpStatus.PAYLOAD_TOO_LARGE, "WEBHOOK_TOO_LARGE", "La notificación excede el tamaño permitido.");
    }
}
