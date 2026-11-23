package com.zondamarket.identity;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Testcontainers
class ProfileContractIT {
    private static final String ME = "/api/v1/me";
    private static final String ADDRESSES = ME + "/addresses";
    private static final String SELLER = ME + "/seller-profile";
    private static final String PUBLIC = "/api/v1/sellers/";
    private static final String COOKIE = "__Host-zonda-refresh";
    private static final String ADDRESS = """
            {"street":" San Martín 200 ","city":"Mendoza","provinceCode":" ar-m ","postalCode":"m5500abc","country":"ar"}
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient sql;

    @Test
    void updatesTheProfileAndRestrictsAddressesToTheirOwner() throws Exception {
        var owner = register();
        var stranger = register();
        mvc.perform(post(ADDRESSES).contentType(MediaType.APPLICATION_JSON).content(ADDRESS))
                .andExpect(status().isUnauthorized());
        mvc.perform(auth(patch(ME), owner).content("""
                {"displayName":"  Lucía Ficticia  ","emailVerified":true,"roles":["ADMIN"]}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Lucía Ficticia"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
        mvc.perform(auth(get(ME), owner)).andExpect(jsonPath("$.displayName").value("Lucía Ficticia"));
        mvc.perform(auth(get(ME), stranger)).andExpect(jsonPath("$.displayName").isEmpty());
        mvc.perform(auth(patch(ME), owner).content("""
                {"displayName":"  "}
                """)).andExpect(status().isBadRequest());

        mvc.perform(auth(post(ADDRESSES), owner).content(ADDRESS.replace("\"ar\"", "\"CL\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post(ADDRESSES), owner).content(ADDRESS.replace("ar-m", "ar-o")))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(post(ADDRESSES), owner).content(ADDRESS.replace("m5500abc", "ABC")))
                .andExpect(status().isBadRequest());
        var saved = mvc.perform(auth(post(ADDRESSES), owner).content(ADDRESS))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.street").value("San Martín 200"))
                .andExpect(jsonPath("$.provinceCode").value("AR-M")).andExpect(jsonPath("$.postalCode").value("M5500ABC"))
                .andReturn();
        String id = JsonPath.read(saved.getResponse().getContentAsString(), "$.id");
        String path = ADDRESSES + "/" + id;
        mvc.perform(auth(get(ADDRESSES), stranger)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(auth(patch(path), stranger).content("""
                {"city":"Otra localidad"}
                """)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
        mvc.perform(auth(delete(path), stranger)).andExpect(status().isNotFound());
        mvc.perform(auth(patch(path), owner).content("""
                {"street":"San Martín 202"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.city").value("Mendoza"));
        mvc.perform(auth(get(ADDRESSES), owner)).andExpect(jsonPath("$[0].street").value("San Martín 202"));
        mvc.perform(auth(delete(path), owner)).andExpect(status().isNoContent());
        mvc.perform(auth(delete(path), owner)).andExpect(status().isNotFound());
        mvc.perform(auth(get(ADDRESSES), owner)).andExpect(jsonPath("$.length()").value(0));
        assertTrue(sql.sql("select deleted from zonda_market.account_address where id = :id")
                .param("id", UUID.fromString(id)).query(Boolean.class).single());
    }

    @Test
    void persistsDeliveryDetailsAndKeepsOneDefaultAddress() throws Exception {
        var owner = register();
        var stranger = register();
        var first = mvc.perform(auth(post(ADDRESSES), owner).content(ADDRESS))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.defaultAddress").value(true)).andReturn();
        String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        mvc.perform(auth(post(ADDRESSES), owner).content("""
                {"street":"Belgrano 50","city":"Mendoza","provinceCode":"AR-M","postalCode":"5500","country":"AR","phone":"abcdef"}
                """)).andExpect(status().isBadRequest());
        var second = mvc.perform(auth(post(ADDRESSES), owner).content("""
                {"street":"Belgrano 50","city":"Mendoza","provinceCode":"AR-M","postalCode":"5500","country":"AR",
                 "recipient":" Ana Ficticia ","phone":"+54 261 555 0100","deliveryDetails":"Piso 2",
                 "defaultAddress":true}
                """)).andExpect(status().isCreated()).andExpect(jsonPath("$.recipient").value("Ana Ficticia"))
                .andExpect(jsonPath("$.phone").value("+54 261 555 0100"))
                .andExpect(jsonPath("$.deliveryDetails").value("Piso 2"))
                .andExpect(jsonPath("$.defaultAddress").value(true)).andReturn();
        String secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.id");
        mvc.perform(auth(patch(ADDRESSES + "/" + secondId), stranger).content("{\"defaultAddress\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(get(ADDRESSES), owner)).andExpect(jsonPath("$[0].defaultAddress").value(false))
                .andExpect(jsonPath("$[1].defaultAddress").value(true));
        mvc.perform(auth(patch(ADDRESSES + "/" + firstId), owner).content("{\"defaultAddress\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.defaultAddress").value(true));
        mvc.perform(auth(patch(ADDRESSES + "/" + secondId), owner).content("{\"deliveryDetails\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliveryDetails").value(""))
                .andExpect(jsonPath("$.phone").value("+54 261 555 0100"));
        mvc.perform(auth(delete(ADDRESSES + "/" + firstId), owner)).andExpect(status().isNoContent());
        mvc.perform(auth(get(ADDRESSES), owner)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(secondId)).andExpect(jsonPath("$[0].defaultAddress").value(true));
    }

    @Test
    void requiresVerificationAndPromotesExactlyOnceUnderConcurrentActivation() throws Exception {
        var owner = register();
        String slug = "taller-" + UUID.randomUUID();
        String profile = profile(slug, "COMERCIO");
        mvc.perform(auth(get(SELLER), owner)).andExpect(status().isNotFound());
        mvc.perform(auth(post(SELLER), owner).content(profile)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        mvc.perform(get(PUBLIC + slug)).andExpect(status().isNotFound());
        verify(owner);

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        MvcResult winner;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var jobs = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Activación concurrente sin iniciar.");
                return mvc.perform(auth(post(SELLER), owner).content(profile)).andReturn();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var results = List.of(jobs.get(0).get(10, TimeUnit.SECONDS), jobs.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(List.of(201, 409), results.stream().map(result -> result.getResponse().getStatus()).sorted().toList());
            winner = results.stream().filter(result -> result.getResponse().getStatus() == 201).findFirst().orElseThrow();
            var loser = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertEquals("SELLER_ALREADY_ACTIVE", JsonPath.read(loser.getResponse().getContentAsString(), "$.code"));
        }
        UUID trace = sql.sql("select activation_trace_id from zonda_market.seller_profile where slug = :slug")
                .param("slug", slug).query(UUID.class).single();
        assertEquals(winner.getResponse().getHeader("X-Trace-Id"), trace.toString());
        var refreshed = mvc.perform(browserCommand("refresh").cookie(owner.getResponse().getCookie(COOKIE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("SELLER")).andReturn();
        String bearer = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken");
        String claims = new String(Base64.getUrlDecoder().decode(bearer.split("\\.")[1]), StandardCharsets.UTF_8);
        assertEquals(List.of("SELLER"), JsonPath.read(claims, "$.roles"));
        mvc.perform(auth(get(SELLER), refreshed)).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(slug));
        mvc.perform(auth(post(ADDRESSES), refreshed).content(ADDRESS)).andExpect(status().isCreated());
        mvc.perform(get(SELLER)).andExpect(status().isUnauthorized());

        var publicProfile = mvc.perform(get(PUBLIC + slug)).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("COMERCIO"))
                .andExpect(jsonPath("$.reviewCount").value(0)).andExpect(jsonPath("$.averageRating").isEmpty())
                .andReturn();
        Map<String, Object> data = JsonPath.read(publicProfile.getResponse().getContentAsString(), "$");
        assertEquals(Set.of("type", "displayName", "slug", "bio", "reviewCount", "averageRating"), data.keySet());
    }

    @Test
    void normalizesTheSlugAndLeavesAnAccountUnchangedOnConflict() throws Exception {
        var first = register();
        var second = register();
        verify(first);
        verify(second);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(auth(post(SELLER), first).content(profile("  PÉÑA  --  " + suffix + "  ", "PERSONAL")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("pena-" + suffix));
        mvc.perform(auth(post(SELLER), second).content(profile("pena-" + suffix, "COMERCIO")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER_SLUG_IN_USE"));
        mvc.perform(auth(get(ME), second)).andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("USER"));
        mvc.perform(auth(get(SELLER), second)).andExpect(status().isNotFound());
        mvc.perform(auth(post(SELLER), second).content(profile("otro-" + suffix, "COMERCIO")))
                .andExpect(status().isCreated());
    }

    private MvcResult register() throws Exception {
        return mvc.perform(browserCommand("register").content("""
                {"email":"%s@example.test","password":"contraseña-solo-de-prueba"}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
    }

    private void verify(MvcResult session) throws Exception {
        var account = mvc.perform(auth(get(ME), session)).andReturn();
        String id = JsonPath.read(account.getResponse().getContentAsString(), "$.id");
        sql.sql("update zonda_market.user_account set email_verified = true where id = :id")
                .param("id", UUID.fromString(id)).update();
    }

    private static String profile(String slug, String type) {
        return """
                {"type":"%s","displayName":"Taller ficticio","slug":"%s","bio":"Productos de prueba"}
                """.formatted(type, slug);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, MvcResult session)
            throws Exception {
        String bearer = JsonPath.read(session.getResponse().getContentAsString(), "$.accessToken");
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).contentType(MediaType.APPLICATION_JSON);
    }

    private static MockHttpServletRequestBuilder browserCommand(String action) {
        return post("/api/v1/auth/" + action).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header("X-Zonda-CSRF", "1").contentType(MediaType.APPLICATION_JSON);
    }
}
