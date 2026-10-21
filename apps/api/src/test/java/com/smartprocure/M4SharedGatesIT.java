package com.smartprocure;

import static org.assertj.core.api.Assertions.*;

import com.smartprocure.common.M1Support;
import com.smartprocure.provider.SalesOrderProviderAdapter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

@SpringBootTest(
    properties = {
      "spring.flyway.schemas=m4_shared_gates_test",
      "spring.flyway.default-schema=m4_shared_gates_test",
      "spring.datasource.hikari.schema=m4_shared_gates_test",
      "app.mock.worker-enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M0_TEST_DB_URL", matches = ".+")
class M4SharedGatesIT extends M4TestSupport {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add(
        "spring.datasource.url",
        () ->
            System.getenv("M0_TEST_DB_URL")
                + (System.getenv("M0_TEST_DB_URL").contains("?") ? "&" : "?")
                + "currentSchema=m4_shared_gates_test");
    p.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("M0_TEST_DB_USER", "postgres"));
    p.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("M0_TEST_DB_PASSWORD", ""));
  }

  @BeforeEach
  void reset() {
    clean();
  }

  void payable(Fixture f) {
    jdbc.update("update payment_policies set is_paused=false where tenant_id=?", A);
    jdbc.update(
        "update sku_mappings set"
            + " status='CONFIRMED',review_acknowledged=true,confirmed_by='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',confirmed_at=now()"
            + " where tenant_id=? and store_id=?",
        A,
        f.store());
    jdbc.update(
        "update supplier_offers set compatible_sales_sku_id='MOCK-SKU-BLUE' where tenant_id=? and"
            + " id=(select supplier_offer_id from purchase_intents where tenant_id=? and"
            + " sales_order_item_id=?)",
        A,
        A,
        f.item());
  }

  UUID caseFor(Fixture f, UUID item) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " aftersales_cases(id,tenant_id,sales_order_id,sales_order_item_id,store_id,source_case_id,type,state,source_state,qty,requested_refund_fen)"
            + " values(?,?,?,?,?,?,'REFUND_ONLY','REQUESTED','REQUESTED',1,100)",
        id,
        A,
        f.order(),
        item,
        f.store(),
        "MOCK-CASE-" + id);
    return id;
  }

  @Test
  void unrelatedItemAftersaleDoesNotFreezePaymentForThisPurchase() throws Exception {
    Fixture f = fixture(false);
    payable(f);
    assertThat(
            get(f.s(), "/api/v1/purchases/" + f.purchase() + "/payment-status")
                .get("state")
                .asText())
        .isEqualTo("WAIT_MERCHANT_PAYMENT");
    caseFor(f, f.other());
    assertThat(
            post(f.s(), "/api/v1/purchases/" + f.purchase() + "/payments", Map.of(), 202)
                .get("state")
                .asText())
        .isEqualTo("WAIT_MERCHANT_PAYMENT");
  }

  @Test
  void localFulfillmentAndHoldReduceEvaluationQuantityWithoutSourceOverwrite() throws Exception {
    Fixture f = fixture(false);
    payable(f);
    jdbc.update(
        "update sales_order_items set shipment_ack_qty=1 where tenant_id=? and id=?", A, f.item());
    assertThat(
            post(
                    f.s(),
                    "/api/v1/purchase-intents/evaluate",
                    Map.of("salesOrderItemId", f.item()),
                    200)
                .get("qty")
                .asInt())
        .isEqualTo(1);
    jdbc.update(
        "update sales_order_items set shipment_ack_qty=0,aftersale_hold_qty=1 where tenant_id=? and"
            + " id=?",
        A,
        f.item());
    assertThat(
            post(
                    f.s(),
                    "/api/v1/purchase-intents/evaluate",
                    Map.of("salesOrderItemId", f.item()),
                    200)
                .get("qty")
                .asInt())
        .isEqualTo(1);
    assertThat(
            post(f.s(), "/api/v1/purchases/" + f.purchase() + "/payments", Map.of(), 202)
                .get("decisionCode")
                .asText())
        .isEqualTo("SALES_ORDER_NOT_PAYABLE");
    assertThat(
            jdbc.queryForObject(
                "select fulfilled_qty+cancelled_qty from sales_order_items where tenant_id=? and"
                    + " id=?",
                Integer.class,
                A,
                f.item()))
        .isZero();
  }

  @Test
  void missingSalesItemWithAftersaleHistoryIsRetainedAndAuthorityStillAdvances() throws Exception {
    Fixture f = fixture(false);
    caseFor(f, f.other());
    removeOtherAuthorityItem(f);
    assertThat(
            jdbc.queryForObject(
                "select source_version from sales_orders where tenant_id=? and id=?",
                Long.class,
                A,
                f.order()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select cancelled_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                f.other()))
        .isEqualTo(1);
  }

  void removeOtherAuthorityItem(Fixture f) throws Exception {
    String external =
        jdbc.queryForObject(
            "select external_order_id from sales_orders where tenant_id=? and id=?",
            String.class,
            A,
            f.order());
    post(
        f.s(),
        "/api/v1/mock/stores/" + f.store() + "/scenarios",
        Map.of("externalOrderId", external, "scenario", "PAID"),
        202);
    String raw =
        jdbc.queryForObject(
            "select snapshot::text from mock_order_authority where tenant_id=? and store_id=? and"
                + " external_order_id=?",
            String.class,
            A,
            f.store(),
            external);
    var before = mapper.readValue(raw, SalesOrderProviderAdapter.Snapshot.class);
    var remaining =
        before.items().stream().filter(i -> i.externalItemId().equals("ITEM-1")).toList();
    var next =
        new SalesOrderProviderAdapter.Snapshot(
            before.externalOrderId(),
            before.sourceStatus(),
            before.sourceVersion(),
            before.currency(),
            8600,
            before.paidAt(),
            before.promisedShipAt(),
            before.sourceUpdatedAt(),
            remaining);
    String json = mapper.writeValueAsString(next);
    jdbc.update(
        "update mock_order_authority set snapshot=?::jsonb,snapshot_digest=? where tenant_id=? and"
            + " store_id=? and external_order_id=?",
        json,
        M1Support.digest(json),
        A,
        f.store(),
        external);
    salesWorker.runOnce();
  }

  @Test
  void acceptedUnconsumedAftersaleSurvivesSalesItemRemoval() throws Exception {
    Fixture f = fixture(false);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        Map.of(
            "eventId",
            "MOCK-PENDING-RETENTION",
            "sourceCaseId",
            "MOCK-CASE-PENDING-RETENTION",
            "type",
            "REFUND_ONLY",
            "sourceState",
            "REFUNDED",
            "sourceVersion",
            1,
            "salesOrderItemId",
            f.other(),
            "qty",
            1,
            "requestedRefundFen",
            4300),
        202);
    removeOtherAuthorityItem(f);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from sales_order_items where tenant_id=? and id=?",
                Long.class,
                A,
                f.other()))
        .isEqualTo(1);
    worker("aftersalesWorker");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from aftersales_cases where tenant_id=? and sales_order_item_id=?",
                Long.class,
                A,
                f.other()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select sum(amount_fen) from refund_transactions r join aftersales_cases c on"
                    + " c.tenant_id=r.tenant_id and c.id=r.aftersales_case_id where c.tenant_id=?"
                    + " and c.sales_order_item_id=? and r.direction='SALES' and r.state='VERIFIED'",
                Long.class,
                A,
                f.other()))
        .isEqualTo(4300);
  }

  @Test
  void heldItemRequiresManualAllocationAndDoesNotAdvertiseAutomaticProcurement() throws Exception {
    Fixture f = fixture(false);
    payable(f);
    jdbc.update("update supplier_offers set last_unit_price_fen=1000 where tenant_id=?", A);
    jdbc.update(
        "update sales_order_items set aftersale_hold_qty=1 where tenant_id=? and id=?",
        A,
        f.item());
    var evaluation =
        post(f.s(), "/api/v1/purchase-intents/evaluate", Map.of("salesOrderItemId", f.item()), 200);
    assertThat(evaluation.get("qty").asInt()).isEqualTo(1);
    assertThat(evaluation.get("allowed").asBoolean()).isFalse();
    assertThat(evaluation.get("reasons").toString()).contains("AFTERSALE_ALLOCATION_REVIEW");
  }
}
