package com.zondamarket.integrations.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zondamarket.catalog.service.ListingMediaStorage.Format;
import com.zondamarket.catalog.service.ListingMediaStorage.StorageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudinaryListingMediaTest {
    private record Request(String authorization, String contentType, String body) {}

    @Test
    void sendsAuthenticatedMultipartAndInvalidatesDeletedAssets() throws Exception {
        UUID id = UUID.randomUUID();
        var requests = new AtomicReference<Request>();
        var deleted = new AtomicReference<Request>();
        var uploadResponse = new AtomicReference<>("""
                {"public_id":"%s","secure_url":"https://res.cloudinary.com/demo/image/upload/v123/%s.png"}
                """.formatted(id, id));
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/v1_1/demo/image/upload", exchange -> {
            requests.set(read(exchange));
            respond(exchange, uploadResponse.get());
        });
        server.createContext("/v1_1/demo/image/destroy", exchange -> {
            deleted.set(read(exchange));
            respond(exchange, "{\"result\":\"not found\"}");
        });
        server.start();
        try {
            var cloud = new CloudinaryListingMedia("demo", "key-ficticia", "secreto-ficticio",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            byte[] fixture = "bytes-de-prueba".getBytes(StandardCharsets.UTF_8);
            var uploaded = cloud.upload(id, new ByteArrayInputStream(fixture), fixture.length, Format.PNG);
            assertTrue(uploaded.url().contains("c_limit,w_1200,h_1200"));
            assertTrue(uploaded.thumbnailUrl().contains("c_fill,w_240,h_240"));
            assertTrue(uploaded.thumbnailUrl().endsWith("/v123/" + id + ".png"));
            var sent = requests.get();
            String auth = "Basic " + Base64.getEncoder().encodeToString("key-ficticia:secreto-ficticio"
                    .getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(auth, sent.authorization());
            assertTrue(sent.contentType().startsWith("multipart/form-data;"));
            assertTrue(sent.body().contains("name=\"public_id\""));
            assertTrue(sent.body().contains("name=\"file\"; filename=\"" + id + ".png\""));
            assertTrue(sent.body().contains("bytes-de-prueba"));

            cloud.delete(id);
            assertEquals(auth, deleted.get().authorization());
            assertTrue(deleted.get().body().contains("public_id=" + id));
            assertTrue(deleted.get().body().contains("invalidate=true"));

            uploadResponse.set("{\"public_id\":\"" + id + "\",\"secure_url\":\"https://otro.example.test\"}");
            assertThrows(StorageException.class,
                    () -> cloud.upload(id, new ByteArrayInputStream(fixture), fixture.length, Format.PNG));
        } finally {
            server.stop(0);
        }
    }

    private static Request read(HttpExchange exchange) throws IOException {
        return new Request(exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }
}
