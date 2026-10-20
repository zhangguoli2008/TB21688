package com.smartprocure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.sales.SalesEventWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.schemas=m1_read_consistency",
        "spring.flyway.default-schema=m1_read_consistency",
        "spring.datasource.hikari.schema=m1_read_consistency",
        "app.mock.worker-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M0_TEST_DB_URL", matches = ".+")
class M1ReadConsistencyIT {
    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SalesEventWorker worker;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean JdbcTemplate jdbc;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("M0_TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("M0_TEST_DB_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("M0_TEST_DB_PASSWORD", ""));
    }

    record Session(MockHttpSession session, String csrf) { }

    Session login() throws Exception {
        var first = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        var session = (MockHttpSession) first.getRequest().getSession();
        String token = data(first).get("token").asText();
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("{\"persona\":\"OWNER_A\"}"))
                .andExpect(status().isOk());
        return new Session(session, data(mvc.perform(get("/api/v1/auth/csrf").session(session))
                .andReturn()).get("token").asText());
    }

    JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    JsonNode detail(Session session, UUID order) throws Exception {
        return data(mvc.perform(get("/api/v1/orders/" + order).session(session.session()))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailNeverMixesHeaderWithItemsAndTimelineFromAConcurrentRefund() throws Exception {
        Session session = login();
        String external = "MOCK-" + UUID.randomUUID();
        JsonNode store = data(mvc.perform(post("/api/v1/stores").session(session.session())
                .header("X-CSRF-TOKEN", session.csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content(mapper.writeValueAsString(Map.of(
                        "platform", "DOUYIN", "externalShopId", "MOCK-" + UUID.randomUUID(), "displayName", "快照测试店铺"))))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/v1/mock/stores/" + store.get("id").asText() + "/scenarios")
                .session(session.session()).header("X-CSRF-TOKEN", session.csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("externalOrderId", external, "scenario", "PAID"))))
                .andExpect(status().isAccepted());
        worker.runOnce();
        UUID order = jdbc.queryForObject("select id from sales_orders where tenant_id=? and external_order_id=?",
                UUID.class, TENANT, external);
        JsonNode before = detail(session, order);
        assertThat(before.get("sourceStatus").asText()).isEqualTo("PAID");
        assertThat(before.get("sourceVersion").asLong()).isEqualTo(1);
        assertThat(before.get("timeline").size()).isEqualTo(1);
        assertThat(before.get("items")).allSatisfy(item -> assertThat(item.get("cancelledQty").asInt()).isZero());

        CountDownLatch headerRead = new CountDownLatch(1);
        CountDownLatch refundCommitted = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object rows = invocation.callRealMethod();
            String sql = invocation.getArgument(0);
            if (sql.startsWith("select o.*,s.display_name,s.platform from sales_orders")
                    && sql.contains(" and o.id=?") && pauseOnce.compareAndSet(true, false)) {
                // The header ResultSet has been fully consumed; no item/timeline query has run yet.
                headerRead.countDown();
                assertThat(refundCommitted.await(5, TimeUnit.SECONDS)).as("concurrent refund must commit").isTrue();
            }
            return rows;
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        var reader = Executors.newSingleThreadExecutor();
        try {
            var pending = reader.submit(() -> detail(session, order));
            assertThat(headerRead.await(5, TimeUnit.SECONDS)).as("detail must reach the header read barrier").isTrue();
            // This thread uses a separate transaction/connection from the blocked HTTP reader.
            JdbcTemplate writer = new JdbcTemplate(dataSource);
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setTimeout(5);
            transaction.executeWithoutResult(ignored -> {
                assertThat(writer.update("update sales_orders set source_status='REFUNDED',local_status='SUSPENDED',source_version=source_version+1,version=version+1 where tenant_id=? and id=?", TENANT, order)).isEqualTo(1);
                assertThat(writer.update("update sales_order_items set cancelled_qty=qty where tenant_id=? and sales_order_id=?", TENANT, order)).isEqualTo(2);
                writer.update("insert into sales_order_timeline(tenant_id,sales_order_id,event_id,action,source_version) values(?,?,?,'AUTHORITY_APPLIED',2)", TENANT, order, "CONCURRENT-" + UUID.randomUUID());
            });
            refundCommitted.countDown();
            JsonNode observed = pending.get(5, TimeUnit.SECONDS);
            JsonNode after = detail(session, order);
            assertThat(after.get("sourceStatus").asText()).isEqualTo("REFUNDED");
            assertThat(after.get("sourceVersion").asLong()).isEqualTo(2);
            assertThat(after.get("timeline").size()).isEqualTo(2);
            assertThat(after.get("items")).allSatisfy(item ->
                    assertThat(item.get("cancelledQty").asInt()).isEqualTo(item.get("qty").asInt()));
            assertThat(observed).as("detail must equal the complete pre-refund or post-refund snapshot")
                    .isIn(before, after);
        } finally {
            refundCommitted.countDown();
            reader.shutdownNow();
            assertThat(reader.awaitTermination(5, TimeUnit.SECONDS)).as("reader must terminate").isTrue();
        }
    }
}
