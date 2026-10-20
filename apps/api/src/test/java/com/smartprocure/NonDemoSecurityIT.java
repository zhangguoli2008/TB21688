package com.smartprocure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.schemas=m0_non_demo", "spring.flyway.default-schema=m0_non_demo", "spring.datasource.hikari.schema=m0_non_demo"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "M0_TEST_DB_URL", matches = ".+")
class NonDemoSecurityIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("M0_TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("M0_TEST_DB_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("M0_TEST_DB_PASSWORD", ""));
    }
    @Test void defaultProfileCreatesNoSyntheticUsersAndRefusesDemoLogin() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tenants", Integer.class)).isZero();
        var result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(result.getResponse().getContentAsString()).at("/data/token").asText();
        mvc.perform(post("/api/v1/auth/login").session((MockHttpSession) result.getRequest().getSession())
                .header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"persona\":\"OWNER_A\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void contractAndSwaggerAreNotPublicAndNoDefaultFormLoginExists() throws Exception {
        for (String route : new String[]{"/swagger-ui/index.html", "/contracts/openapi.yaml", "/v3/api-docs", "/login"}) {
            mvc.perform(get(route)).andExpect(status().is4xxClientError());
        }
    }
    @Test void mockWebhookIsUnavailableOutsideDemoEvenWithHeaders() throws Exception {
        mvc.perform(post("/api/v1/webhooks/mock/stores/11111111-1111-4111-8111-111111111111")
                .header("X-Mock-Timestamp",Long.toString(java.time.Instant.now().getEpochSecond()))
                .header("X-Mock-Nonce",java.util.UUID.randomUUID().toString()).header("X-Mock-Signature","00".repeat(32))
                .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from inbox_events",Integer.class)).isZero();
    }
}
