package com.zondamarket.catalog;

import com.jayway.jsonpath.JsonPath;
import com.zondamarket.catalog.service.ListingMediaCleanup;
import com.zondamarket.catalog.service.ListingMediaStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "zonda.media.worker-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@Import(ListingPublicationIT.MediaFixture.class)
class ListingPublicationIT {
    private static final String PRIVATE = "/api/v1/seller/listings";
    private static final String PUBLIC = "/api/v1/listings/";
    private static final String CLOTHING = "e2010000-0000-4000-8000-000000000001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient sql;
    @Autowired FixtureStorage storage;
    @Autowired ListingMediaCleanup cleanup;

    @BeforeEach
    void resetCleanupFixture() {
        sql.sql("delete from zonda_market.listing_media_cleanup").update();
        storage.assets.clear();
        storage.failUpload.set(false);
        storage.failDelete.set(false);
        storage.uploads = null;
    }

    @Test
    void publishesTheGalleryAndVariantsAndArchivesWithoutLosingReferences() throws Exception {
        String owner = seller();
        String stranger = seller();
        String path = draft(owner);
        mvc.perform(auth(post(path + "/archive"), owner)).andExpect(status().isConflict());
        mvc.perform(auth(post(path + "/publish"), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LISTING_INCOMPLETE"));
        var first = upload(path, owner, "frente.jpg", image("jpeg"), "  Frente  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.alt").value("Frente")).andReturn();
        String firstId = field(first, "id");
        String secondId = field(upload(path, owner, "lateral.png", image("png"), "Lateral")
                .andExpect(status().isCreated()).andReturn(), "id");
        mvc.perform(auth(post(path + "/publish"), owner)).andExpect(status().isConflict());
        addVariant(path, owner, "negro", "Negro", 3);
        addVariant(path, owner, "azul", "Azul", 0);

        mvc.perform(auth(patch(path + "/images/" + secondId), owner)
                .content("{\"alt\":\"Vista de costado\",\"position\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(secondId))
                .andExpect(jsonPath("$[1].id").value(firstId)).andExpect(jsonPath("$[1].position").value(1));
        mvc.perform(auth(patch(path + "/images/" + secondId), owner).content("{\"position\":2}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_POSITION_INVALID"));

        mvc.perform(post(path + "/publish")).andExpect(status().isUnauthorized());
        mvc.perform(auth(post(path + "/publish"), stranger)).andExpect(status().isNotFound());
        mvc.perform(auth(post(path + "/archive"), stranger)).andExpect(status().isNotFound());
        mvc.perform(auth(patch(path + "/images/" + secondId), stranger).content("{\"alt\":\"ajena\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(delete(path + "/images/" + secondId), stranger)).andExpect(status().isNotFound());
        upload(path, stranger, "ajena.png", image("png"), "").andExpect(status().isNotFound());
        mvc.perform(get(PUBLIC + "inexistente")).andExpect(status().isNotFound());

        var published = mvc.perform(auth(post(path + "/publish"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVA")).andExpect(jsonPath("$.version").value(1)).andReturn();
        String slug = field(published, "slug");
        UUID listingId = UUID.fromString(path.substring(PRIVATE.length() + 1));
        assertEquals(published.getResponse().getHeader("X-Trace-Id"),
                sql.sql("select trace_id from zonda_market.listing_status_change where listing_id = :id")
                        .param("id", listingId).query(UUID.class).single().toString());

        mvc.perform(get(PUBLIC + slug)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.images[0].alt").value("Vista de costado"))
                .andExpect(jsonPath("$.images[0].thumbnailUrl").isString())
                .andExpect(jsonPath("$.seller.displayName").value("Taller ficticio"))
                .andExpect(jsonPath("$.skus[0].price").value("1200.50"))
                .andExpect(jsonPath("$.skus[0].available").value(true))
                .andExpect(jsonPath("$.skus[1].available").value(false))
                .andExpect(jsonPath("$.skus[0].availableStock").doesNotExist())
                .andExpect(jsonPath("$.skus[0].reservedStock").doesNotExist());

        sql.sql("update zonda_market.seller_profile set deleted = true where id = (select seller_id from zonda_market.listing where id = :id)")
                .param("id", listingId).update();
        mvc.perform(get(PUBLIC + slug)).andExpect(status().isNotFound());
        sql.sql("update zonda_market.seller_profile set deleted = false where id = (select seller_id from zonda_market.listing where id = :id)")
                .param("id", listingId).update();

        mvc.perform(auth(delete(path + "/images/" + firstId), owner)).andExpect(status().isNoContent());
        mvc.perform(auth(delete(path + "/images/" + secondId), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMAGE_REQUIRED"));
        mvc.perform(auth(post(path + "/archive"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVADA")).andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.images[0].id").value(secondId)).andExpect(jsonPath("$.skus.length()").value(2));
        mvc.perform(get(PUBLIC + slug)).andExpect(status().isNotFound());
        assertTrue(cleanup.deleteNext());
        assertEquals(1, storage.assets.size());
        assertTrue(sql.sql("select deleted from zonda_market.listing_image where id = :id")
                .param("id", UUID.fromString(firstId)).query(Boolean.class).single());
        mvc.perform(auth(post(path + "/publish"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug));
        mvc.perform(get(PUBLIC + slug)).andExpect(status().isOk());
    }

    @Test
    void validatesBytesAndCompensatesAnUnconfirmedUploadWithRetry() throws Exception {
        String owner = seller();
        String path = draft(owner);
        upload(path, owner, "falso.png", "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8), "")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_INVALID"));
        upload(path, owner, "grande.png", new byte[5 * 1024 * 1024 + 1], "")
                .andExpect(status().is(413)).andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));

        byte[] webp = Base64.getDecoder().decode("UklGRjgAAABXRUJQVlA4ICwAAADQAQCdASoIAAgAAUAmJaACdLoB+AADsAD+7U3f/A/Lpwx4f/FnIxHZeAAAAA==");
        upload(path, owner, "minimo.webp", webp, "").andExpect(status().isCreated());
        storage.failUpload.set(true);
        upload(path, owner, "huérfana.png", image("png"), "").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEDIA_UNAVAILABLE"));
        mvc.perform(auth(get(path), owner)).andExpect(status().isOk()).andExpect(jsonPath("$.images.length()").value(1));
        assertEquals(2, storage.assets.size());

        assertFalse(cleanup.deleteNext());
        var orphan = sql.sql("select public_id from zonda_market.listing_media_cleanup where cancelled_at is null")
                .query(UUID.class).single();
        makeDue(orphan);
        storage.failDelete.set(true);
        assertTrue(cleanup.deleteNext());
        assertTrue(storage.assets.contains(orphan));
        assertFalse(cleanup.deleteNext());
        makeDue(orphan);
        assertTrue(cleanup.deleteNext());
        assertFalse(storage.assets.contains(orphan));
        assertEquals(2, sql.sql("select attempts from zonda_market.listing_media_cleanup where public_id = :id and completed_at is not null")
                .param("id", orphan).query(Integer.class).single());
    }

    @Test
    void compensatesTheLosingUploadWhenTwoUploadsCompeteForTheSixthSlot() throws Exception {
        String owner = seller();
        String path = draft(owner);
        byte[] png = image("png");
        for (int index = 0; index < 5; index++) {
            upload(path, owner, "imagen.png", png, "").andExpect(status().isCreated());
        }
        storage.uploads = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> upload(path, owner, "primera.png", png, "").andReturn());
            var second = executor.submit(() -> upload(path, owner, "segunda.png", png, "").andReturn());
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(201, 409), results.stream().map(result -> result.getResponse().getStatus()).sorted().toList());
            var rejected = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertEquals("IMAGE_LIMIT_REACHED", field(rejected, "code"));
        }
        assertEquals(7, storage.assets.size());
        assertTrue(cleanup.deleteNext());
        assertEquals(6, storage.assets.size());
        mvc.perform(auth(get(path), owner)).andExpect(status().isOk()).andExpect(jsonPath("$.images.length()").value(6));
    }

    private void makeDue(UUID id) {
        sql.sql("update zonda_market.listing_media_cleanup set next_attempt_at = now() - interval '1 second' where public_id = :id")
                .param("id", id).update();
    }

    private ResultActions upload(String path, String token, String filename, byte[] bytes, String alt) throws Exception {
        return mvc.perform(multipart(path + "/images")
                .file(new MockMultipartFile("file", filename, "image/png", bytes)).param("alt", alt)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String seller() throws Exception {
        var registered = mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s@example.test","password":"contraseña-solo-de-prueba"}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        String token = field(registered, "accessToken");
        var account = mvc.perform(auth(get("/api/v1/me"), token)).andExpect(status().isOk()).andReturn();
        sql.sql("update zonda_market.user_account set email_verified = true where id = :id")
                .param("id", UUID.fromString(field(account, "id"))).update();
        mvc.perform(auth(post("/api/v1/me/seller-profile"), token).contentType(MediaType.APPLICATION_JSON).content("""
                {"displayName":"Taller ficticio","type":"PERSONAL","slug":"taller-%s"}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated());
        return token;
    }

    private String draft(String token) throws Exception {
        var saved = mvc.perform(auth(post(PRIVATE), token).contentType(MediaType.APPLICATION_JSON).content("""
                {"categoryId":"%s","title":"Lámpara ficticia","description":"Descripción de prueba","specs":{}}
                """.formatted(CLOTHING))).andExpect(status().isCreated()).andReturn();
        return PRIVATE + "/" + field(saved, "id");
    }

    private void addVariant(String path, String token, String name, String color, int stock) throws Exception {
        mvc.perform(auth(post(path + "/skus"), token).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"%s-%s","attributes":{"color":"%s","talle":"M"},"price":1200.50,"initialStock":%d}
                """.formatted(name, UUID.randomUUID(), color, stock))).andExpect(status().isCreated());
    }

    private static byte[] image(String format) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), format, bytes);
        return bytes.toByteArray();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static String field(MvcResult result, String name) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$." + name);
    }

    @TestConfiguration
    static class MediaFixture {
        @Bean
        FixtureStorage fixtureStorage() {
            return new FixtureStorage();
        }
    }

    static class FixtureStorage implements ListingMediaStorage {
        final Set<UUID> assets = ConcurrentHashMap.newKeySet();
        final AtomicBoolean failUpload = new AtomicBoolean();
        final AtomicBoolean failDelete = new AtomicBoolean();
        volatile CyclicBarrier uploads;

        @Override
        public String provider() { return "fixture"; }

        @Override
        public StoredImage upload(UUID publicId, InputStream content, long size, Format format) {
            assets.add(publicId);
            if (uploads != null) {
                try {
                    uploads.await(8, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
            if (failUpload.getAndSet(false)) throw new StorageException("Respuesta no confirmada de prueba.");
            String url = "https://images.example.test/" + publicId + format.extension();
            return new StoredImage(url, url + "?miniatura=1");
        }

        @Override
        public void delete(UUID publicId) {
            if (failDelete.getAndSet(false)) throw new StorageException("Borrado no confirmado de prueba.");
            assets.remove(publicId);
        }
    }
}
