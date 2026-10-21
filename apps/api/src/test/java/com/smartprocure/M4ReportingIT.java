package com.smartprocure;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.sales.SalesEventWorker;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.flyway.schemas=m4_reporting_test",
      "spring.flyway.default-schema=m4_reporting_test",
      "spring.datasource.hikari.schema=m4_reporting_test",
      "app.mock.worker-enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M0_TEST_DB_URL", matches = ".+")
class M4ReportingIT {
  static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationContext context;
  @Autowired SalesEventWorker salesWorker;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add(
        "spring.datasource.url",
        () ->
            System.getenv("M0_TEST_DB_URL")
                + (System.getenv("M0_TEST_DB_URL").contains("?") ? "&" : "?")
                + "currentSchema=m4_reporting_test");
    p.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("M0_TEST_DB_USER", "postgres"));
    p.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("M0_TEST_DB_PASSWORD", ""));
  }

  static class ControlledClock extends java.time.Clock {
    final java.util.concurrent.atomic.AtomicReference<java.time.Instant> current =
        new java.util.concurrent.atomic.AtomicReference<>();

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public java.time.Instant instant() {
      var value = current.get();
      return value == null ? java.time.Instant.now() : value;
    }
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class ClockConfig {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    ControlledClock controlledClock() {
      return new ControlledClock();
    }
  }

  @Autowired ControlledClock clock;

  @BeforeEach
  void cleanSyntheticBusinessFixtures() {
    clock.current.set(null);
    jdbc.execute("truncate stores,supplier_accounts cascade");
    jdbc.update("delete from audit_logs");
    jdbc.update("delete from m4_mock_events");
    jdbc.update(
        "update payment_policies set"
            + " mode='MANUAL',is_paused=true,max_single_fen=50000,max_daily_fen=200000,max_store_single_fen=50000,max_store_daily_fen=200000,min_estimated_profit_fen=0,new_supplier_first_order_manual=true");
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource("db/demo/m1-seed.sql"),
            new org.springframework.core.io.ClassPathResource("db/demo/m2-seed.sql"),
            new org.springframework.core.io.ClassPathResource("db/demo/m3-seed.sql"))
        .execute(jdbc.getDataSource());
  }

  record Session(MockHttpSession session, String csrf) {}

  record Fixture(
      Session s, UUID store, UUID item, UUID offer, UUID account, UUID purchase, String external) {}

  JsonNode data(org.springframework.test.web.servlet.MvcResult r) throws Exception {
    return mapper.readTree(r.getResponse().getContentAsString()).get("data");
  }

  Session login(String persona) throws Exception {
    var r = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
    var s = (MockHttpSession) r.getRequest().getSession();
    String token = data(r).get("token").asText();
    mvc.perform(
            post("/api/v1/auth/login")
                .session(s)
                .header("X-CSRF-TOKEN", token)
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("persona", persona))))
        .andExpect(status().isOk());
    return new Session(
        s,
        data(mvc.perform(get("/api/v1/auth/csrf").session(s)).andReturn()).get("token").asText());
  }

  JsonNode policy(Session s) throws Exception {
    return data(
        mvc.perform(get("/api/v1/payment-policy").session(s.session()))
            .andExpect(status().isOk())
            .andReturn());
  }

  void policy(Session s, String mode, boolean paused, long daily, long storeDaily)
      throws Exception {
    JsonNode old = policy(s);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("mode", mode);
    p.put("maxSingleFen", 50000);
    p.put("maxDailyFen", daily);
    p.put("maxStoreSingleFen", 50000);
    p.put("maxStoreDailyFen", storeDaily);
    p.put("minEstimatedProfitFen", 0);
    p.put("isPaused", paused);
    p.put("newSupplierFirstOrderManual", true);
    p.put("version", old.get("version").asLong());
    mvc.perform(
            put("/api/v1/payment-policy")
                .session(s.session())
                .header("X-CSRF-TOKEN", s.csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(mapper.writeValueAsString(p)))
        .andExpect(status().isOk());
  }

  Fixture fixture(String mode, String behavior, boolean mandate) throws Exception {
    Session s = login("OWNER_A");
    policy(s, mode, false, 200000, 200000);
    UUID store =
        UUID.fromString(
            data(mvc.perform(
                        post("/api/v1/stores")
                            .session(s.session())
                            .header("X-CSRF-TOKEN", s.csrf())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType("application/json")
                            .content(
                                mapper.writeValueAsString(
                                    Map.of(
                                        "platform",
                                        "DOUYIN",
                                        "externalShopId",
                                        "MOCK-" + UUID.randomUUID(),
                                        "displayName",
                                        "M3合成店"))))
                    .andExpect(status().isOk())
                    .andReturn())
                .get("id")
                .asText());
    String external = "MOCK-" + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/mock/stores/" + store + "/scenarios")
                .session(s.session())
                .header("X-CSRF-TOKEN", s.csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        Map.of("externalOrderId", external, "scenario", "PAID"))))
        .andExpect(status().isAccepted());
    salesWorker.runOnce();
    UUID item =
        jdbc.queryForObject(
            "select i.id from sales_order_items i join sales_orders o on o.tenant_id=i.tenant_id"
                + " and o.id=i.sales_order_id where i.tenant_id=? and o.external_order_id=? and"
                + " i.external_item_id='ITEM-1'",
            UUID.class,
            A,
            external);
    UUID account = UUID.randomUUID(), offer = UUID.randomUUID();
    jdbc.update(
        "insert into supplier_accounts(id,tenant_id,provider,external_account_id,status)"
            + " values(?,?,'MOCK_SUPPLIER',?,'ACTIVE')",
        account,
        A,
        "MOCK-" + account);
    jdbc.update(
        "insert into"
            + " supplier_offers(id,tenant_id,supplier_account_id,external_offer_id,external_sku_id,display_name,last_unit_price_fen,shipping_fen,compatible_sales_sku_id)"
            + " values(?,?,?,?,?,'合成蓝盒',1001,99,'MOCK-SKU-BLUE')",
        offer,
        A,
        account,
        "MOCK-" + offer,
        "MOCK-SKU-BLUE");
    jdbc.update(
        "insert into"
            + " sku_mappings(tenant_id,store_id,sales_sku_id,supplier_offer_id,status,confirmed_by,confirmed_at,review_acknowledged)"
            + " values(?,?,'MOCK-SKU-BLUE',?,'CONFIRMED','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',now(),true)",
        A,
        store,
        offer);
    JsonNode evaluation =
        data(
            mvc.perform(
                    post("/api/v1/purchase-intents/evaluate")
                        .session(s.session())
                        .header("X-CSRF-TOKEN", s.csrf())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("salesOrderItemId", item))))
                .andExpect(status().isOk())
                .andReturn());
    mvc.perform(
            post("/api/v1/purchase-intents")
                .session(s.session())
                .header("X-CSRF-TOKEN", s.csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "salesOrderItemId",
                            item,
                            "quoteId",
                            evaluation.at("/selectedQuote/id").asText()))))
        .andExpect(status().isAccepted());
    context
        .getBean("procurementWorker")
        .getClass()
        .getMethod("runOnce")
        .invoke(context.getBean("procurementWorker"));
    UUID purchase =
        jdbc.queryForObject(
            "select p.id from purchase_orders p join purchase_intents i on i.tenant_id=p.tenant_id"
                + " and i.id=p.purchase_intent_id where i.tenant_id=? and i.sales_order_item_id=?",
            UUID.class,
            A,
            item);
    if (mandate)
      jdbc.update(
          "insert into"
              + " payment_mandates(tenant_id,supplier_account_id,provider,status,max_single_fen,expires_at,last_verified_at)"
              + " values(?,?,'MOCK_PAYMENT','ACTIVE',50000,now()+interval '30 days',now())",
          A,
          account);
    mvc.perform(
            patch("/api/v1/mock/purchases/" + purchase + "/payment-config")
                .session(s.session())
                .header("X-CSRF-TOKEN", s.csrf())
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("behavior", behavior, "version", 0))))
        .andExpect(status().isOk());
    return new Fixture(s, store, item, offer, account, purchase, external);
  }

  JsonNode command(Fixture f, String suffix, String key, int status) throws Exception {
    return data(
        mvc.perform(
                post("/api/v1/purchases/" + f.purchase() + "/" + suffix)
                    .session(f.s().session())
                    .header("X-CSRF-TOKEN", f.s().csrf())
                    .header("Idempotency-Key", key))
            .andExpect(status().is(status))
            .andReturn());
  }

  JsonNode view(Fixture f) throws Exception {
    return data(
        mvc.perform(
                get("/api/v1/purchases/" + f.purchase() + "/payment-status")
                    .session(f.s().session()))
            .andExpect(status().isOk())
            .andReturn());
  }

  void worker() throws Exception {
    context
        .getBean("paymentWorker")
        .getClass()
        .getMethod("runOnce")
        .invoke(context.getBean("paymentWorker"));
  }

  void due(Fixture f) {
    jdbc.update(
        "update outbox_events e set available_at=now()-interval '1 second' from payment_attempts a"
            + " where e.tenant_id=a.tenant_id and e.aggregate_id=a.id and a.tenant_id=? and"
            + " a.purchase_order_id=? and e.state='RETRY_WAIT'",
        A,
        f.purchase());
  }

  long calls(Fixture f) {
    return jdbc.queryForObject(
        "select coalesce(sum(pay_calls),0) from mock_payment_authority where tenant_id=? and"
            + " purchase_order_id=?",
        Long.class,
        A,
        f.purchase());
  }

  void receipt(Fixture f, UUID attempt, String outcome) throws Exception {
    mvc.perform(
            post("/api/v1/mock/payment-attempts/" + attempt + "/receipt")
                .session(f.s().session())
                .header("X-CSRF-TOKEN", f.s().csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("outcome", outcome))))
        .andExpect(status().isAccepted());
  }

  void reports() throws Exception {
    context
        .getBean("reportProjectionWorker")
        .getClass()
        .getMethod("runOnce")
        .invoke(context.getBean("reportProjectionWorker"));
  }

  JsonNode overview(Session s, String query) throws Exception {
    return data(
        mvc.perform(get("/api/v1/reports/overview" + query).session(s.session()))
            .andExpect(status().isOk())
            .andReturn());
  }

  @Test
  void emptyReportDistinguishesCashFlowFromUnmeasuredProfit() throws Exception {
    Session s = login("OWNER_A");
    JsonNode v = overview(s, "?from=2020-01-01T00:00:00Z&to=2020-01-02T00:00:00Z");
    assertThat(v.get("orderCount").asLong()).isZero();
    assertThat(v.get("salesPaidFen").asLong()).isZero();
    assertThat(v.get("actualNetProfitFen").isNull()).isTrue();
    assertThat(v.get("profitStatus").asText()).isEqualTo("INCOMPLETE_COSTS");
    assertThat(v.get("basis").asText()).isEqualTo("ORDER_CREATED_COHORT");
    assertThat(v.get("mode").asText()).isEqualTo("MOCK");
  }

  @Test
  void paidProcurementIsProjectedOnceAndNeverCalledNetProfit() throws Exception {
    Fixture f = fixture("AUTO", "NORMAL", true);
    command(f, "payments", UUID.randomUUID().toString(), 202);
    worker();
    reports();
    JsonNode v = overview(f.s(), "?storeId=" + f.store());
    assertThat(v.get("salesPaidFen").asLong()).isEqualTo(12900);
    assertThat(v.get("purchasePaidFen").asLong()).isEqualTo(2101);
    assertThat(v.get("netCashFlowFen").asLong()).isEqualTo(10799);
    assertThat(v.get("actualNetProfitFen").isNull()).isTrue();
    reports();
    assertThat(overview(f.s(), "?storeId=" + f.store()).get("purchasePaidFen").asLong())
        .isEqualTo(2101);
    Session b = login("OWNER_B");
    mvc.perform(get("/api/v1/reports/orders?storeId=" + f.store()).session(b.session()))
        .andExpect(status().isNotFound());
    Session support = login("SUPPORT_A");
    mvc.perform(get("/api/v1/reports/overview").session(support.session()))
        .andExpect(status().isForbidden());
  }

  @Test
  void reportRejectsUnboundedOrReversedPeriodsAndUnauthenticatedAccess() throws Exception {
    Session s = login("OWNER_A");
    for (String q :
        List.of(
            "?from=2020-01-01T00:00:00Z&to=2022-01-01T00:00:00Z",
            "?from=2022-01-01T00:00:00Z&to=2020-01-01T00:00:00Z",
            "?from=wrong"))
      mvc.perform(get("/api/v1/reports/overview" + q).session(s.session()))
          .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/reports/overview")).andExpect(status().isUnauthorized());
  }

  @Test
  void activityDeduplicatesNotificationsAndReadIsPerUserAndScope() throws Exception {
    Fixture f = fixture("MANUAL", "NORMAL", false);
    UUID order =
        jdbc.queryForObject(
            "select sales_order_id from sales_order_items where tenant_id=? and id=?",
            UUID.class,
            A,
            f.item());
    var activity = context.getBean(com.smartprocure.common.M4Activity.class);
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    for (int i = 0; i < 2; i++)
      transaction.executeWithoutResult(
          x ->
              activity.record(
                  A,
                  order,
                  null,
                  "SHIPPING",
                  "MOCK_SHIPMENT_ACKED",
                  "PURCHASE",
                  f.purchase(),
                  "SHIPMENT_ACKED",
                  "test-notify-one",
                  true));
    JsonNode list =
        data(
            mvc.perform(get("/api/v1/notifications?storeId=" + f.store()).session(f.s().session()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(list.get("total").asLong()).isEqualTo(1);
    String id = list.at("/items/0/id").asText();
    mvc.perform(
            post("/api/v1/notifications/" + id + "/read")
                .session(f.s().session())
                .header("X-CSRF-TOKEN", f.s().csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.readAt").isNotEmpty());
    mvc.perform(
            get("/api/v1/notifications?unread=true&storeId=" + f.store()).session(f.s().session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
    Session support = login("SUPPORT_A");
    mvc.perform(get("/api/v1/orders/" + order + "/activity").session(support.session()))
        .andExpect(status().isNotFound());
    jdbc.update(
        "insert into member_store_access(tenant_id,tenant_member_id,store_id) select tenant_id,id,?"
            + " from tenant_members where tenant_id=? and"
            + " user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2'",
        f.store(),
        A);
    mvc.perform(
            get("/api/v1/notifications?unread=true&storeId=" + f.store())
                .session(support.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1));
    mvc.perform(get("/api/v1/orders/" + order + "/activity").session(support.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.code == 'SHIPMENT_ACKED')]").isNotEmpty());
    Session b = login("OWNER_B");
    mvc.perform(
            post("/api/v1/notifications/" + id + "/read")
                .session(b.session())
                .header("X-CSRF-TOKEN", b.csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
  }

  void aftersalesWorker() throws Exception {
    context
        .getBean("aftersalesWorker")
        .getClass()
        .getMethod("runOnce")
        .invoke(context.getBean("aftersalesWorker"));
  }

  void sourceRefund(Fixture f, String sourceCase, String event, String state, long version)
      throws Exception {
    UUID order =
        jdbc.queryForObject(
            "select sales_order_id from sales_order_items where tenant_id=? and id=?",
            UUID.class,
            A,
            f.item());
    mvc.perform(
            post("/api/v1/mock/orders/" + order + "/aftersale-events")
                .session(f.s().session())
                .header("X-CSRF-TOKEN", f.s().csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "eventId",
                            event,
                            "sourceCaseId",
                            sourceCase,
                            "type",
                            "REFUND_ONLY",
                            "sourceState",
                            state,
                            "sourceVersion",
                            version,
                            "salesOrderItemId",
                            f.item(),
                            "qty",
                            1,
                            "requestedRefundFen",
                            4300))))
        .andExpect(status().isAccepted());
    aftersalesWorker();
  }

  void supplierRefund(Fixture f, UUID caseId, String event, String state, long version, long amount)
      throws Exception {
    mvc.perform(
            post("/api/v1/mock/aftersales/" + caseId + "/supplier-events")
                .session(f.s().session())
                .header("X-CSRF-TOKEN", f.s().csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "eventId",
                            event,
                            "purchaseOrderId",
                            f.purchase(),
                            "sourceVersion",
                            version,
                            "state",
                            state,
                            "refundAmountFen",
                            amount))))
        .andExpect(status().isAccepted());
    aftersalesWorker();
  }

  @Test
  void t019RefundDirectionsAndGrossPaymentHistoryRemainSeparateAndNoJoinFanout() throws Exception {
    Fixture f = fixture("AUTO", "NORMAL", true);
    command(f, "payments", UUID.randomUUID().toString(), 202);
    worker();
    sourceRefund(f, "MOCK-CASE-REPORT", "MOCK-REPORT-REQUESTED", "REQUESTED", 1);
    UUID caseId =
        jdbc.queryForObject(
            "select id from aftersales_cases where tenant_id=? and"
                + " source_case_id='MOCK-CASE-REPORT'",
            UUID.class,
            A);
    supplierRefund(f, caseId, "MOCK-REPORT-PENDING", "REFUND_PENDING", 1, 0);
    sourceRefund(f, "MOCK-CASE-REPORT", "MOCK-REPORT-REFUNDED", "REFUNDED", 2);
    reports();
    JsonNode v = overview(f.s(), "?storeId=" + f.store());
    assertThat(v.get("salesPaidFen").asLong()).isEqualTo(12900);
    assertThat(v.get("purchasePaidFen").asLong()).isEqualTo(2101);
    assertThat(v.get("salesRefundedFen").asLong()).isEqualTo(4300);
    assertThat(v.get("supplierRefundedFen").asLong()).isZero();
    assertThat(v.get("netCashFlowFen").asLong()).isEqualTo(6499);
    assertThat(v.get("pendingSupplierRefundFen").asLong()).isEqualTo(1050);
    supplierRefund(f, caseId, "MOCK-REPORT-SUPPLIER-REFUNDED", "REFUNDED", 2, 1050);
    supplierRefund(f, caseId, "MOCK-REPORT-SUPPLIER-REFUNDED", "REFUNDED", 2, 1050);
    reports();
    v = overview(f.s(), "?storeId=" + f.store());
    assertThat(v.get("supplierRefundedFen").asLong()).isEqualTo(1050);
    assertThat(v.get("salesRefundedFen").asLong()).isEqualTo(4300);
    assertThat(v.get("purchasePaidFen").asLong()).isEqualTo(2101);
    assertThat(v.get("netCashFlowFen").asLong()).isEqualTo(7549);
    assertThat(v.get("estimatedProfitFen").asLong()).isEqualTo(6499);
    assertThat(v.get("actualNetProfitFen").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refund_transactions where tenant_id=? and"
                    + " aftersales_case_id=? and state='VERIFIED'",
                Long.class,
                A,
                caseId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select state from purchase_orders where tenant_id=? and id=?",
                String.class,
                A,
                f.purchase()))
        .isEqualTo("PAID");
  }
}
