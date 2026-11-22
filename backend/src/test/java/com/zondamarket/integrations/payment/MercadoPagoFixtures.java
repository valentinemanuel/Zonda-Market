package com.zondamarket.integrations.payment;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

final class MercadoPagoFixtures {
    private MercadoPagoFixtures() {}

    static JsonObject read(String filename) {
        try {
            var contents = new ClassPathResource("mercadopago/" + filename).getContentAsString(StandardCharsets.UTF_8);
            return JsonParser.parseString(contents).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
