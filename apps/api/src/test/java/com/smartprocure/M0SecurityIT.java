package com.smartprocure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M0_TEST_DB_URL", matches = ".+")
class M0SecurityIT {
    static final String A = "11111111-1111-4111-8111-111111111111";
    static final String B = "22222222-2222-4222-8222-222222222222";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.smartprocure.audit.AuditService audit;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping routes;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("M0_TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("M0_TEST_DB_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("M0_TEST_DB_PASSWORD", ""));
    }

    record Session(MockHttpSession session, String csrf) { }
    Session anonymous() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN")).andReturn();
        return new Session((MockHttpSession) result.getRequest().getSession(), mapper.readTree(result.getResponse().getContentAsString()).at("/data/token").asText());
    }
    Session login(String persona) throws Exception {
        Session before = anonymous();
        mvc.perform(post("/api/v1/auth/login").session(before.session()).header("X-CSRF-TOKEN", before.csrf())
                .contentType("application/json").content("{\"persona\":\"" + persona + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("MOCK"));
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").session(before.session())).andReturn();
        return new Session(before.session(), mapper.readTree(result.getResponse().getContentAsString()).at("/data/token").asText());
    }

    @Test void unauthenticatedResponseHasSafeMatchingRequestId() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/me").header("X-Request-Id", "bad-input"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andReturn();
        String id = mapper.readTree(result.getResponse().getContentAsString()).get("requestId").asText();
        UUID.fromString(id);
        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo(id);
    }
    @Test void loginAndLogoutRequireCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"persona\":\"OWNER_A\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        Session user = login("OWNER_A");
        mvc.perform(post("/api/v1/auth/logout").session(user.session())).andExpect(status().isForbidden());
    }
    @Test void sessionRotationAndLogoutRevokeIdentity() throws Exception {
        Session before = anonymous();
        String oldId = before.session().getId();
        mvc.perform(post("/api/v1/auth/login").session(before.session()).header("X-CSRF-TOKEN", before.csrf())
                .contentType("application/json").content("{\"persona\":\"OWNER_A\"}"))
                .andExpect(status().isOk());
        assertThat(before.session().getId()).isNotEqualTo(oldId);
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf").session(before.session())).andReturn();
        String token = mapper.readTree(csrf.getResponse().getContentAsString()).at("/data/token").asText();
        mvc.perform(post("/api/v1/auth/logout").session(before.session()).header("X-CSRF-TOKEN", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.loggedOut").value(true));
        assertThat(before.session().isInvalid()).isTrue();
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }
    @Test void serverTenantCannotBeOverriddenByClientOrResourceId() throws Exception {
        Session user = login("OWNER_A");
        mvc.perform(get("/api/v1/me").session(user.session()).header("X-Tenant-Id", B).queryParam("tenant_id", B))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentTenantId").value(A));
        mvc.perform(get("/api/v1/demo/resources/" + A).session(user.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value(A));
        mvc.perform(get("/api/v1/demo/resources/" + B).session(user.session()).header("X-Tenant-Id", B))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        Session other = login("OWNER_B");
        mvc.perform(get("/api/v1/demo/resources/" + B).session(other.session())).andExpect(status().isOk());
    }
    @Test void rbacIsEnforcedOnDirectApiAccess() throws Exception {
        Session support = login("SUPPORT_A");
        mvc.perform(get("/api/v1/system/status").session(support.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/demo/resources/" + A).session(support.session())).andExpect(status().isOk());
        Session owner = login("OWNER_A");
        mvc.perform(get("/api/v1/system/status").session(owner.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realChannelsEnabled").value(false))
                .andExpect(jsonPath("$.data.services[0].status").value("UP"))
                .andExpect(jsonPath("$.data.services[1].status").value("UP"));
    }
    @Test void membershipIsRevalidatedOnEveryRequest() throws Exception {
        Session user = login("OWNER_A");
        jdbc.update("update tenant_members set status='DISABLED' where tenant_id=? and user_id=?", UUID.fromString(A), UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"));
        try { mvc.perform(get("/api/v1/me").session(user.session())).andExpect(status().isUnauthorized()); }
        finally { jdbc.update("update tenant_members set status='ACTIVE' where tenant_id=?", UUID.fromString(A)); }
    }
    @Test void disabledMemberCannotLoginOrAcquireASessionIdentity() throws Exception {
        Session before = anonymous();
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
        String requestId = "01234567-89ab-4cde-8fab-0123456789ab";
        jdbc.update("update tenant_members set status='DISABLED' where tenant_id=? and user_id=?", UUID.fromString(A), userId);
        try {
            mvc.perform(post("/api/v1/auth/login").session(before.session()).header("X-CSRF-TOKEN", before.csrf())
                    .header("X-Request-Id", requestId).contentType("application/json").content("{\"persona\":\"OWNER_A\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.requestId").value(requestId)).andExpect(header().string("X-Request-Id", requestId));
            assertThat(before.session().getAttribute(com.smartprocure.auth.DemoIdentity.SESSION_ATTRIBUTE)).isNull();
            mvc.perform(get("/api/v1/me").session(before.session())).andExpect(status().isUnauthorized());
        } finally { jdbc.update("update tenant_members set status='ACTIVE' where tenant_id=? and user_id=?", UUID.fromString(A), userId); }
    }
    @Test void invalidInputsAndUnimplementedRoutesUseErrorEnvelope() throws Exception {
        Session user = login("OWNER_A");
        mvc.perform(post("/api/v1/auth/login").session(user.session()).header("X-CSRF-TOKEN", user.csrf())
                .contentType("application/json").content("{\"persona\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/demo/resources/not-a-uuid").session(user.session())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").session(user.session())).andExpect(status().isOk());
        mvc.perform(put("/api/v1/me").session(user.session()).header("X-CSRF-TOKEN", user.csrf()))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
    @Test void migratedSchemaAndSeedCanRepeatWithOnlySyntheticStoresAndNoPayments() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        var populator = new ResourceDatabasePopulator(new ClassPathResource("db/demo/seed.sql"),new ClassPathResource("db/demo/m1-seed.sql"));
        populator.execute(jdbc.getDataSource());
        populator.execute(jdbc.getDataSource());
        assertThat(jdbc.queryForObject("select count(*) from tenants", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from stores", Integer.class)).isGreaterThanOrEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from payment_attempts", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("insert into users(email,password_hash) values ('OWNER.A@EXAMPLE.INVALID','!disabled')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into roles(tenant_id,name,permissions) values (?,'bad','[1]'::jsonb)", UUID.fromString(A)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void enabledRealDatabaseFlagFailsClosed() throws Exception {
        Session user = login("OWNER_A");
        jdbc.update("insert into feature_flags(flag_name,enabled) values ('real_payment',true)");
        try { mvc.perform(get("/api/v1/me").session(user.session())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CAPABILITY_UNAVAILABLE")); }
        finally { jdbc.update("delete from feature_flags where flag_name='real_payment'"); }
    }
    @Test void auditHasOnlyMinimalSafeFieldsAndUtcTimestamps() throws Exception {
        Session user = login("OWNER_A");
        String id = "01234567-89ab-4cde-8fab-0123456789ab";
        mvc.perform(get("/api/v1/me").session(user.session()).header("X-Request-Id", id)).andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", id));
        JsonNode details = mapper.readTree(jdbc.queryForObject("select redacted_details::text from audit_logs where request_id=? order by created_at desc limit 1", String.class, id));
        assertThat(details.fieldNames()).toIterable().containsExactlyInAnyOrder("outcome", "httpStatus");
        jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set time zone 'Pacific/Honolulu'");
                try (var result = statement.executeQuery("select extract(timezone from created_at)::int from audit_logs limit 1")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(-36000);
                }
            } finally { try (var reset = connection.createStatement()) { reset.execute("set time zone 'UTC'"); } }
            return null;
        });
    }
    @Test void swaggerLoadsTheSingleContractAndNeverGeneratesAnotherContract() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/vendor/swagger-ui-bundle.js")).andExpect(status().isOk());
        mvc.perform(get("/contracts/openapi.yaml")).andExpect(status().isOk());
        assertThat(routes.getHandlerMethods().keySet().stream().flatMap(mapping -> mapping.getPatternValues().stream()))
                .noneMatch(path -> path.startsWith("/v3/api-docs"));
    }
    @Test void auditWriteFailureCannotCreateAnAuthenticatedSessionOrReturnSuccess() throws Exception {
        Session before = anonymous();
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic outage"))
                .when(audit).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
        try {
            mvc.perform(post("/api/v1/auth/login").session(before.session()).header("X-CSRF-TOKEN", before.csrf())
                    .contentType("application/json").content("{\"persona\":\"OWNER_A\"}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
            assertThat(before.session().isInvalid()).isTrue();
        } finally { org.mockito.Mockito.reset(audit); }
    }
    @Test void requestAuditFailureBuffersSuccessAndRevokesExistingIdentity() throws Exception {
        Session user = login("OWNER_A");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic outage"))
                .when(audit).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
        try {
            mvc.perform(get("/api/v1/me").session(user.session())).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE")).andExpect(jsonPath("$.data").doesNotExist());
            assertThat(user.session().isInvalid()).isTrue();
        } finally { org.mockito.Mockito.reset(audit); }
    }
    @Test void tenantRealFlagCannotDisableAnotherTenantOrPublicCsrf() throws Exception {
        Session alice = login("OWNER_A");
        Session bob = login("OWNER_B");
        jdbc.update("insert into feature_flags(tenant_id,flag_name,enabled) values (?,'real_payment',true)", UUID.fromString(B));
        try {
            mvc.perform(get("/api/v1/me").session(alice.session())).andExpect(status().isOk());
            mvc.perform(get("/api/v1/me").session(bob.session())).andExpect(status().isServiceUnavailable());
            Session before = anonymous();
            mvc.perform(post("/api/v1/auth/login").session(before.session()).header("X-CSRF-TOKEN", before.csrf())
                    .contentType("application/json").content("{\"persona\":\"OWNER_B\"}"))
                    .andExpect(status().isServiceUnavailable());
        } finally { jdbc.update("delete from feature_flags where flag_name='real_payment' and tenant_id=?", UUID.fromString(B)); }
    }
    @Test void mockPaymentFlagDoesNotActivateOrDisableRealChannels() throws Exception {
        Session user = login("OWNER_A");
        jdbc.update("insert into feature_flags(flag_name,enabled) values ('mock_payment_demo',true)");
        try { mvc.perform(get("/api/v1/me").session(user.session())).andExpect(status().isOk()); }
        finally { jdbc.update("delete from feature_flags where flag_name='mock_payment_demo'"); }
    }
    @Test void databaseRejectsDuplicateGlobalFlagsAndCrossTenantRoles() {
        jdbc.update("insert into feature_flags(flag_name,enabled) values ('m0_constraint_probe',false)");
        try {
            assertThatThrownBy(() -> jdbc.update("insert into feature_flags(flag_name,enabled) values ('m0_constraint_probe',false)"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally { jdbc.update("delete from feature_flags where flag_name='m0_constraint_probe'"); }
        assertThatThrownBy(() -> jdbc.update("insert into tenant_members(tenant_id,user_id,role_id) values (?,?,?)",
                UUID.fromString(B), UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"), UUID.fromString("a0000000-0000-4000-8000-000000000001")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
