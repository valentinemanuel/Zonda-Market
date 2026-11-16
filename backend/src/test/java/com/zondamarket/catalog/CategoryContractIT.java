package com.zondamarket.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Sql(statements = """
        INSERT INTO zonda_market.category (id, name, slug, parent_id, created_at, updated_at, deleted) VALUES
        ('e0020000-0000-4000-8000-000000000002', 'Audio', 'audio', 'e0020000-0000-4000-8000-000000000001',
         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
        ('e0020000-0000-4000-8000-000000000003', 'Archivada', 'archivada', NULL,
         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, TRUE)
        ON CONFLICT (id) DO NOTHING;
        """)
class CategoryContractIT {
    private static final String CATEGORY_PATH = "/api/v1/categories/{id}";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void readsTheSeededRoot() throws Exception {
        mvc.perform(get(CATEGORY_PATH, "e0020000-0000-4000-8000-000000000001"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("e0020000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.name").value("Tecnología"))
                .andExpect(jsonPath("$.slug").value("tecnologia"))
                .andExpect(jsonPath("$.parentId").value(nullValue()));
    }

    @Test
    void includesTheParentIdForAChild() throws Exception {
        mvc.perform(get(CATEGORY_PATH, "E0020000-0000-4000-8000-000000000002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Audio"))
                .andExpect(jsonPath("$.parentId").value("e0020000-0000-4000-8000-000000000001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sin-uuid", "1-1-1-1-1"})
    void rejectsInvalidUuids(String id) throws Exception {
        mvc.perform(get(CATEGORY_PATH, id))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("id"))
                .andExpect(jsonPath("$.errors[0].message").value("Usá un UUID válido."))
                .andExpect(hasCorrelatedTrace());
    }

    @ParameterizedTest
    @ValueSource(strings = {"e0020000-0000-4000-8000-000000000003", "e0020000-0000-4000-8000-000000000099"})
    void hidesArchivedAndAbsentCategories(String id) throws Exception {
        mvc.perform(get(CATEGORY_PATH, id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"))
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(hasCorrelatedTrace());
    }

    @Test
    void publishesStatusCodesAndMediaTypesInSwagger() throws Exception {
        String responses = "$.paths['/api/v1/categories/{id}'].get.responses";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(responses + "['200'].content['application/json']").exists())
                .andExpect(jsonPath(responses + "['400'].content['application/problem+json']").exists())
                .andExpect(jsonPath(responses + "['404'].content['application/problem+json']").exists());
    }

    private static ResultMatcher hasCorrelatedTrace() {
        return result -> {
            String trace = result.getResponse().getHeader("X-Trace-Id");
            assertNotNull(trace);
            assertDoesNotThrow(() -> UUID.fromString(trace));
            jsonPath("$.traceId").value(trace).match(result);
            jsonPath("$.instance").value(result.getRequest().getRequestURI()).match(result);
        };
    }
}
