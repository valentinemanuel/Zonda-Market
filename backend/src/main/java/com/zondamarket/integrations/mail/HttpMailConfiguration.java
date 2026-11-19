package com.zondamarket.integrations.mail;

import com.zondamarket.shared.mail.MailSender;
import com.zondamarket.shared.mail.MailSender.DeliveryException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Configuration
class HttpMailConfiguration {
    @Bean
    @ConditionalOnProperty(name = "zonda.mail.provider", havingValue = "resend")
    MailSender resend(@Value("${zonda.mail.resend-api-key}") String apiKey,
                      @Value("${zonda.mail.from}") String from) {
        if (apiKey.isBlank() || from.isBlank() || from.endsWith(".test")) {
            throw new IllegalArgumentException("Configurá RESEND_API_KEY y MAIL_FROM con un remitente autorizado.");
        }
        var client = client().defaultHeaders(headers -> headers.setBearerAuth(apiKey)).build();
        var endpoint = URI.create("https://api.resend.com/emails");
        return (id, to, subject, text) -> send(client.post().uri(endpoint)
                .header("Idempotency-Key", id.toString()),
                Map.of("from", from, "to", List.of(to), "subject", subject, "text", text), "id");
    }

    @Bean
    @ConditionalOnProperty(name = "zonda.mail.provider", havingValue = "mailpit")
    MailSender mailpit(@Value("${zonda.mail.mailpit-url}") URI origin,
                       @Value("${zonda.mail.from}") String from) {
        var client = client().build();
        var endpoint = origin.resolve("/api/v1/send");
        return (id, to, subject, text) -> send(client.post().uri(endpoint),
                Map.of("From", Map.of("Email", from), "To", List.of(Map.of("Email", to)),
                        "Subject", subject, "Text", text, "Headers", Map.of("X-Zonda-Event-Id", id.toString())),
                "ID");
    }

    private static RestClient.Builder client() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory);
    }

    private static String send(RestClient.RequestBodySpec request, Map<String, ?> body, String idField) {
        try {
            var response = request.contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(Map.class);
            if (response != null && response.get(idField) instanceof String id && !id.isBlank()) {
                return id;
            }
            throw new DeliveryException("Respuesta de correo sin identificador.");
        } catch (RestClientException exception) {
            // The provider's response can include the recipient or the link.
            throw new DeliveryException("El proveedor de correo no confirmó el envío.");
        }
    }
}
