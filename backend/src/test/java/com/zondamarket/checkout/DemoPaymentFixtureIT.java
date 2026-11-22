package com.zondamarket.checkout;

import com.jayway.jsonpath.JsonPath;
import com.zondamarket.checkout.service.LocalPaymentPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "zonda.auth.jwt-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles({"local", "demo"})
class DemoPaymentFixtureIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectProvider<LocalPaymentPort> adapter;

    @Test
    void demoDisablesTheEndpointAndAdapterEvenWhenLocalIsAlsoActive() throws Exception {
        assertNull(adapter.getIfAvailable());
        var registered = mvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000").header("X-Zonda-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"demo-fixture@example.test","password":"password-ficticia-demo"}
                                """))
                .andExpect(status().isCreated()).andReturn();
        String bearer = JsonPath.read(registered.getResponse().getContentAsString(), "$.accessToken");
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/test-fixtures/orders/" + id + "/payment-outcome")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"outcome":"APPROVED","reference":"local-%s"}
                                """.formatted(id)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/test-fixtures/orders/" + id + "/payments/" + id + "/outcome")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APROBADO\"}"))
                .andExpect(status().isNotFound());
    }
}
