package com.smartprocure;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

@SpringBootTest(
    properties = {
      "spring.flyway.schemas=m4_aftersales_test",
      "spring.flyway.default-schema=m4_aftersales_test",
      "spring.datasource.hikari.schema=m4_aftersales_test",
      "app.mock.worker-enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M0_TEST_DB_URL", matches = ".+")
class M4AftersalesIT extends M4TestSupport {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add(
        "spring.datasource.url",
        () -> System.getenv("M0_TEST_DB_URL") + "?currentSchema=m4_aftersales_test");
    p.add("spring.datasource.username", () -> System.getenv("M0_TEST_DB_USER"));
    p.add("spring.datasource.password", () -> System.getenv("M0_TEST_DB_PASSWORD"));
  }

  @BeforeEach
  void reset() {
    clean();
  }

  Map<String, Object> event(Fixture f, String source, String state, long version) {
    return Map.of(
        "eventId",
        "MOCK-" + UUID.randomUUID(),
        "sourceCaseId",
        source,
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
        4300);
  }

  @Test
  void t011PaidPurchaseDownstreamRefundRequiresSupplierCoordination() throws Exception {
    var f = fixture(true);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-ONE", "REFUNDED", 1),
        202);
    worker("aftersalesWorker");
    var d = get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0");
    assertThat(d.get("state").asText()).isEqualTo("SUPPLIER_COORDINATION");
    assertThat(d.get("upstreamState").asText()).isEqualTo("PENDING");
    String id = d.get("id").asText();
    var detail = get(f.s(), "/api/v1/aftersales/" + id);
    assertThat(detail.get("refunds").size()).isEqualTo(1);
    assertThat(detail.at("/refunds/0/direction").asText()).isEqualTo("SALES");
    assertThat(detail.at("/refunds/0/amountFen").asLong()).isEqualTo(4300);
    assertThat(detail.at("/coordinations/0/requestedRefundFen").asLong()).isEqualTo(1000);
    assertThat(
            jdbc.queryForObject(
                "select state from payment_attempts where tenant_id=? and purchase_order_id=?",
                String.class,
                A,
                f.purchase()))
        .isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "select source_status from sales_orders where tenant_id=? and id=?",
                String.class,
                A,
                f.order()))
        .isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "select aftersale_hold_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                f.other()))
        .isZero();
  }

  @Test
  void supplierRefundIsVerifiedSeparatelyAndLateEventsCannotDuplicateLedger() throws Exception {
    var f = fixture(true);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-TWO", "REFUNDED", 1),
        202);
    worker("aftersalesWorker");
    String id =
        get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0/id").asText();
    post(
        f.s(),
        "/api/v1/mock/aftersales/" + id + "/supplier-events",
        Map.of(
            "eventId",
            "MOCK-REFUND-ONE",
            "purchaseOrderId",
            f.purchase(),
            "sourceVersion",
            2,
            "state",
            "REFUNDED",
            "refundAmountFen",
            1000),
        202);
    assertThat(get(f.s(), "/api/v1/aftersales/" + id).get("refunds").size()).isEqualTo(1);
    worker("aftersalesWorker");
    var d = get(f.s(), "/api/v1/aftersales/" + id);
    assertThat(d.get("state").asText()).isEqualTo("RESOLVED");
    assertThat(d.get("refunds").size()).isEqualTo(2);
    post(
        f.s(),
        "/api/v1/mock/aftersales/" + id + "/supplier-events",
        Map.of(
            "eventId",
            "MOCK-LATE-ONE",
            "purchaseOrderId",
            f.purchase(),
            "sourceVersion",
            1,
            "state",
            "PENDING",
            "refundAmountFen",
            0),
        202);
    worker("aftersalesWorker");
    assertThat(get(f.s(), "/api/v1/aftersales/" + id).get("refunds").size()).isEqualTo(2);
    assertThat(get(f.s(), "/api/v1/aftersales/" + id).get("upstreamState").asText())
        .isEqualTo("REFUNDED");
  }

  @Test
  void acceptedPartialCaseImmediatelyFreezesOnlyAffectedItem() throws Exception {
    var f = fixture(false);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-GAP", "REQUESTED", 1),
        202);
    assertThat(
            jdbc.queryForObject(
                "select aftersale_hold_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                f.item()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select aftersale_hold_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                f.other()))
        .isZero();
    var eval =
        post(f.s(), "/api/v1/purchase-intents/evaluate", Map.of("salesOrderItemId", f.item()), 200);
    assertThat(eval.get("qty").asInt()).isEqualTo(1);
  }

  @Test
  void paymentUnknownNeverBecomesRefundedBySupplierEvent() throws Exception {
    var f = fixture(true);
    jdbc.update(
        "update payment_attempts set state='UNKNOWN' where tenant_id=? and purchase_order_id=?",
        A,
        f.purchase());
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-UNKNOWN", "REFUNDED", 1),
        202);
    worker("aftersalesWorker");
    var c = get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0");
    assertThat(c.get("upstreamState").asText()).isEqualTo("QUERY_PAYMENT");
    String id = c.get("id").asText();
    post(
        f.s(),
        "/api/v1/mock/aftersales/" + id + "/supplier-events",
        Map.of(
            "eventId",
            "MOCK-UNKNOWN-EVENT",
            "purchaseOrderId",
            f.purchase(),
            "sourceVersion",
            1,
            "state",
            "REFUNDED",
            "refundAmountFen",
            0),
        202);
    worker("aftersalesWorker");
    assertThat(get(f.s(), "/api/v1/aftersales/" + id).get("upstreamState").asText())
        .isEqualTo("QUERY_PAYMENT");
    assertThat(
            jdbc.queryForObject(
                "select state from payment_attempts where tenant_id=? and purchase_order_id=?",
                String.class,
                A,
                f.purchase()))
        .isEqualTo("UNKNOWN");
  }

  @Test
  void refundCapsRejectOverAllocationAndCrossTenantScope() throws Exception {
    var f = fixture(true);
    var bad = new LinkedHashMap<>(event(f, "MOCK-CASE-CAP", "REFUNDED", 1));
    bad.put("requestedRefundFen", 4301);
    post(f.s(), "/api/v1/mock/orders/" + f.order() + "/aftersale-events", bad, 409);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-CAP", "REFUNDED", 1),
        202);
    worker("aftersalesWorker");
    String id =
        get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0/id").asText();
    post(
        f.s(),
        "/api/v1/mock/aftersales/" + id + "/supplier-events",
        Map.of(
            "eventId",
            "MOCK-CAP-EVENT",
            "purchaseOrderId",
            f.purchase(),
            "sourceVersion",
            1,
            "state",
            "REFUNDED",
            "refundAmountFen",
            1001),
        409);
    var b = login("OWNER_B");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/aftersales/" + id)
                .session(b.session()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
    var support = login("SUPPORT_A");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/aftersales/" + id)
                .session(support.session()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
  }

  @Test
  void higherSourceVersionCannotChangeItemIdentityBeforeWorker() throws Exception {
    var f = fixture(true);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-IDENTITY", "REQUESTED", 1),
        202);
    var changed = new LinkedHashMap<>(event(f, "MOCK-CASE-IDENTITY", "REQUESTED", 2));
    changed.put("salesOrderItemId", f.other());
    post(f.s(), "/api/v1/mock/orders/" + f.order() + "/aftersale-events", changed, 409);
    assertThat(
            jdbc.queryForObject(
                "select aftersale_hold_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                f.other()))
        .isZero();
  }

  @Test
  void partialSupplierRefundAndPaidCancellationCannotCloseCase() throws Exception {
    var f = fixture(true);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-PARTIAL", "REFUNDED", 1),
        202);
    worker("aftersalesWorker");
    String id =
        get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0/id").asText();
    post(
        f.s(),
        "/api/v1/mock/aftersales/" + id + "/supplier-events",
        Map.of(
            "eventId",
            "MOCK-PARTIAL-REFUND",
            "purchaseOrderId",
            f.purchase(),
            "sourceVersion",
            1,
            "state",
            "REFUNDED",
            "refundAmountFen",
            400),
        202);
    worker("aftersalesWorker");
    assertThat(get(f.s(), "/api/v1/aftersales/" + id).get("state").asText())
        .isEqualTo("SUPPLIER_COORDINATION");
    post(
        f.s(),
        "/api/v1/mock/aftersales/" + id + "/supplier-events",
        Map.of(
            "eventId",
            "MOCK-PAID-CANCEL",
            "purchaseOrderId",
            f.purchase(),
            "sourceVersion",
            2,
            "state",
            "CANCELLED",
            "refundAmountFen",
            0),
        202);
    worker("aftersalesWorker");
    assertThat(get(f.s(), "/api/v1/aftersales/" + id).get("state").asText())
        .isEqualTo("SUPPLIER_COORDINATION");
  }

  @Test
  void explicitReconcileQueuesAuthorityWork() throws Exception {
    var f = fixture(true);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-RECON", "REQUESTED", 1),
        202);
    worker("aftersalesWorker");
    String id =
        get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0/id").asText();
    post(f.s(), "/api/v1/aftersales/" + id + "/reconcile", Map.of(), 200);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_events where tenant_id=? and aggregate_id=? and"
                    + " event_type='M4_AFTERSALE_RECONCILE'",
                Integer.class,
                A,
                UUID.fromString(id)))
        .isEqualTo(1);
  }

  @Test
  void requiredRefundAmountCannotDefaultToZero() throws Exception {
    var f = fixture(true);
    var e = new LinkedHashMap<>(event(f, "MOCK-CASE-REQUIRED", "REQUESTED", 1));
    e.remove("requestedRefundFen");
    post(f.s(), "/api/v1/mock/orders/" + f.order() + "/aftersale-events", e, 400);
  }

  @Test
  void equalSupplierVersionDifferentStateIsConflict() throws Exception {
    var f = fixture(true);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-SUPVER", "REQUESTED", 1),
        202);
    worker("aftersalesWorker");
    String id =
        get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0/id").asText();
    post(
        f.s(),
        "/api/v1/mock/aftersales/" + id + "/supplier-events",
        Map.of(
            "eventId",
            "MOCK-SUPVER-1",
            "purchaseOrderId",
            f.purchase(),
            "sourceVersion",
            1,
            "state",
            "PENDING",
            "refundAmountFen",
            0),
        202);
    post(
        f.s(),
        "/api/v1/mock/aftersales/" + id + "/supplier-events",
        Map.of(
            "eventId",
            "MOCK-SUPVER-2",
            "purchaseOrderId",
            f.purchase(),
            "sourceVersion",
            1,
            "state",
            "REFUNDED",
            "refundAmountFen",
            1000),
        409);
  }

  @Test
  void t010AcceptedRefundBlocksRealQueuedProcurementBeforeAftersalesWorker() throws Exception {
    var f = fixture(false);
    jdbc.update("delete from purchase_orders where tenant_id=? and id=?", A, f.purchase());
    jdbc.update(
        "delete from purchase_intents where tenant_id=? and sales_order_item_id=?", A, f.item());
    jdbc.update(
        "update supplier_offers set"
            + " compatible_sales_sku_id='MOCK-SKU-BLUE',last_unit_price_fen=1000,shipping_fen=0"
            + " where tenant_id=? and supplier_account_id in (select supplier_account_id from"
            + " sku_mappings m join supplier_offers o on o.tenant_id=m.tenant_id and"
            + " o.id=m.supplier_offer_id where m.tenant_id=? and m.store_id=?)",
        A,
        A,
        f.store());
    jdbc.update(
        "update sku_mappings set"
            + " status='CONFIRMED',review_acknowledged=true,confirmed_by='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',confirmed_at=now()"
            + " where tenant_id=? and store_id=?",
        A,
        f.store());
    var evaluation =
        post(f.s(), "/api/v1/purchase-intents/evaluate", Map.of("salesOrderItemId", f.item()), 200);
    assertThat(evaluation.get("allowed").asBoolean()).isTrue();
    post(
        f.s(),
        "/api/v1/purchase-intents",
        Map.of(
            "salesOrderItemId", f.item(), "quoteId", evaluation.at("/selectedQuote/id").asText()),
        202);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-T010", "REFUNDED", 1),
        202);
    worker("procurementWorker");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mock_purchase_authority where tenant_id=?", Integer.class, A))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from purchase_orders where tenant_id=?", Integer.class, A))
        .isZero();
  }

  @Test
  void returnSentAndSupplierReceiptDoNotInventRefund() throws Exception {
    var f = fixture(true);
    shipment(f, "MOCK-PKG-RETURN", 1, "MOCK-TRACK-RETURN", "NORMAL");
    worker("shipmentWorker");
    var e = new LinkedHashMap<>(event(f, "MOCK-CASE-RETURN", "REQUESTED", 1));
    e.put("type", "RETURN_REFUND");
    post(f.s(), "/api/v1/mock/orders/" + f.order() + "/aftersale-events", e, 202);
    worker("aftersalesWorker");
    var c = get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0");
    assertThat(c.get("upstreamState").asText()).isEqualTo("RETURN_REQUIRED");
    String id = c.get("id").asText();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/v1/aftersales/" + id)
                .session(f.s().session())
                .header("X-CSRF-TOKEN", f.s().csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        Map.of("action", "RETURN_SENT", "version", c.get("version").asLong()))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    post(
        f.s(),
        "/api/v1/mock/aftersales/" + id + "/supplier-events",
        Map.of(
            "eventId",
            "MOCK-RETURN-RECEIVED",
            "purchaseOrderId",
            f.purchase(),
            "sourceVersion",
            1,
            "state",
            "RETURN_RECEIVED",
            "refundAmountFen",
            0),
        202);
    worker("aftersalesWorker");
    var d = get(f.s(), "/api/v1/aftersales/" + id);
    assertThat(d.get("state").asText()).isNotEqualTo("RESOLVED");
    assertThat(d.get("refunds").size()).isZero();
  }

  @Test
  void reconcileAfterVerifiedPaymentRestoresProportionalRefundCap() throws Exception {
    var f = fixture(true);
    jdbc.update(
        "update payment_attempts set state='UNKNOWN' where tenant_id=? and purchase_order_id=?",
        A,
        f.purchase());
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-PAID-LATER", "REFUNDED", 1),
        202);
    worker("aftersalesWorker");
    String id =
        get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0/id").asText();
    post(f.s(), "/api/v1/aftersales/" + id + "/reconcile", Map.of(), 200);
    worker("aftersalesWorker");
    assertThat(
            jdbc.queryForObject(
                "select payload->>'queryOnly' from outbox_events where tenant_id=? and"
                    + " event_type='M3_PAYMENT'",
                String.class,
                A))
        .isEqualTo("true");
    jdbc.update(
        "update payment_attempts set state='PAID',last_verified_at=now() where tenant_id=? and"
            + " purchase_order_id=?",
        A,
        f.purchase());
    post(f.s(), "/api/v1/aftersales/" + id + "/reconcile", Map.of(), 200);
    worker("aftersalesWorker");
    var d = get(f.s(), "/api/v1/aftersales/" + id);
    assertThat(d.get("upstreamState").asText()).isEqualTo("PENDING");
    assertThat(d.at("/coordinations/0/requestedRefundFen").asLong()).isEqualTo(1000);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payment_attempts where tenant_id=? and purchase_order_id=?",
                Integer.class,
                A,
                f.purchase()))
        .isEqualTo(1);
  }

  @Test
  void reconcileReadsSupplierAuthorityWhenOriginalNotificationIsBlocked() throws Exception {
    var f = fixture(true);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-QUERY-AUTH", "REFUNDED", 1),
        202);
    worker("aftersalesWorker");
    String id =
        get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0/id").asText();
    post(
        f.s(),
        "/api/v1/mock/aftersales/" + id + "/supplier-events",
        Map.of(
            "eventId",
            "MOCK-QUERY-AUTH-SUP",
            "purchaseOrderId",
            f.purchase(),
            "sourceVersion",
            1,
            "state",
            "REFUNDED",
            "refundAmountFen",
            1000),
        202);
    jdbc.update(
        "update outbox_events set state='BLOCKED' where tenant_id=? and"
            + " event_type='M4_AFTERSALE_SUPPLIER'",
        A);
    post(f.s(), "/api/v1/aftersales/" + id + "/reconcile", Map.of(), 200);
    worker("aftersalesWorker");
    var d = get(f.s(), "/api/v1/aftersales/" + id);
    assertThat(d.get("upstreamState").asText()).isEqualTo("REFUNDED");
    assertThat(d.get("refunds").size()).isEqualTo(2);
  }

  @Test
  void reconcileAppliesNewSourceAuthorityWhenSourceNotificationWasBlocked() throws Exception {
    var f = fixture(true);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-NEW-SOURCE", "REQUESTED", 1),
        202);
    worker("aftersalesWorker");
    String id =
        get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0/id").asText();
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-NEW-SOURCE", "REFUNDED", 2),
        202);
    jdbc.update(
        "update outbox_events set state='BLOCKED' where tenant_id=? and"
            + " event_type='M4_AFTERSALE_SOURCE' and state='NEW'",
        A);
    post(f.s(), "/api/v1/aftersales/" + id + "/reconcile", Map.of(), 200);
    worker("aftersalesWorker");
    var d = get(f.s(), "/api/v1/aftersales/" + id);
    assertThat(d.get("sourceVersion").asLong()).isEqualTo(2);
    assertThat(d.get("sourceState").asText()).isEqualTo("REFUNDED");
    assertThat(d.get("refunds").size()).isEqualTo(1);
  }

  @Test
  void unchangedReconcilePreservesDispatchedReturn() throws Exception {
    var f = fixture(true);
    var e = new LinkedHashMap<>(event(f, "MOCK-CASE-RETURN-PROGRESS", "REQUESTED", 1));
    e.put("type", "RETURN_REFUND");
    post(f.s(), "/api/v1/mock/orders/" + f.order() + "/aftersale-events", e, 202);
    worker("aftersalesWorker");
    var c = get(f.s(), "/api/v1/aftersales?salesOrderId=" + f.order()).at("/items/0");
    String id = c.get("id").asText();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/v1/aftersales/" + id)
                .session(f.s().session())
                .header("X-CSRF-TOKEN", f.s().csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        Map.of("action", "RETURN_SENT", "version", c.get("version").asLong()))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    post(f.s(), "/api/v1/aftersales/" + id + "/reconcile", Map.of(), 200);
    worker("aftersalesWorker");
    assertThat(get(f.s(), "/api/v1/aftersales/" + id).get("state").asText())
        .isEqualTo("RETURN_IN_TRANSIT");
  }

  @Test
  void sameStoreExternalCaseCannotBeAcceptedForAnotherOrder() throws Exception {
    var f = fixture(true);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        event(f, "MOCK-CASE-STORE-UNIQUE", "REQUESTED", 1),
        202);
    String external = "MOCK-" + UUID.randomUUID();
    post(
        f.s(),
        "/api/v1/mock/stores/" + f.store() + "/scenarios",
        Map.of("externalOrderId", external, "scenario", "PAID"),
        202);
    salesWorker.runOnce();
    UUID order =
        jdbc.queryForObject(
            "select id from sales_orders where tenant_id=? and store_id=? and external_order_id=?",
            UUID.class,
            A,
            f.store(),
            external);
    UUID item =
        jdbc.queryForObject(
            "select id from sales_order_items where tenant_id=? and sales_order_id=? and"
                + " external_item_id='ITEM-1'",
            UUID.class,
            A,
            order);
    var duplicate = new LinkedHashMap<>(event(f, "MOCK-CASE-STORE-UNIQUE", "REQUESTED", 1));
    duplicate.put("salesOrderItemId", item);
    post(f.s(), "/api/v1/mock/orders/" + order + "/aftersale-events", duplicate, 409);
    assertThat(
            jdbc.queryForObject(
                "select aftersale_hold_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                item))
        .isZero();
  }
}
