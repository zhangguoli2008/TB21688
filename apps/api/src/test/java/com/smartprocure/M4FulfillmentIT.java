package com.smartprocure;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

@SpringBootTest(
    properties = {
      "spring.flyway.schemas=m4_fulfillment_test",
      "spring.flyway.default-schema=m4_fulfillment_test",
      "spring.datasource.hikari.schema=m4_fulfillment_test",
      "app.mock.worker-enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M0_TEST_DB_URL", matches = ".+")
class M4FulfillmentIT extends M4TestSupport {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add(
        "spring.datasource.url",
        () -> System.getenv("M0_TEST_DB_URL") + "?currentSchema=m4_fulfillment_test");
    p.add("spring.datasource.username", () -> System.getenv("M0_TEST_DB_USER"));
    p.add("spring.datasource.password", () -> System.getenv("M0_TEST_DB_PASSWORD"));
  }

  @BeforeEach
  void reset() {
    clean();
  }

  @Test
  void t012TwoPackagesAcknowledgeIndependentQuantitiesOnlyOnce() throws Exception {
    var f = fixture(true);
    shipment(f, "MOCK-PKG-ONE", 1, "MOCK-TRACK-ONE", "NORMAL");
    shipment(f, "MOCK-PKG-TWO", 1, "MOCK-TRACK-TWO", "NORMAL");
    worker("shipmentWorker");
    var list = get(f.s(), "/api/v1/shipments?purchaseOrderId=" + f.purchase());
    assertThat(list.get("total").asInt()).isEqualTo(2);
    assertThat(list.at("/items/0/currentSync/state").asText()).isEqualTo("ACKED");
    assertThat(list.at("/items/1/currentSync/state").asText()).isEqualTo("ACKED");
    assertThat(
            jdbc.queryForObject(
                "select shipment_ack_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                f.item()))
        .isEqualTo(2);
    worker("shipmentWorker");
    assertThat(
            jdbc.queryForObject(
                "select shipment_ack_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                f.item()))
        .isEqualTo(2);
  }

  @Test
  void t013FailureRemainsIndependentAndExplicitRetryUsesStableKey() throws Exception {
    var f = fixture(true);
    shipment(f, "MOCK-PKG-FAIL", 1, "MOCK-TRACK-FAIL", "SUBMIT_5XX");
    worker("shipmentWorker");
    var list = get(f.s(), "/api/v1/shipments?purchaseOrderId=" + f.purchase());
    String id = list.at("/items/0/id").asText();
    assertThat(list.at("/items/0/currentSync/state").asText()).isEqualTo("RETRY_WAIT");
    var d = get(f.s(), "/api/v1/shipments/" + id);
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/v1/mock/shipments/" + id + "/sync-config")
                .session(f.s().session())
                .header("X-CSRF-TOKEN", f.s().csrf())
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    mapper.writeValueAsString(
                        java.util.Map.of(
                            "behavior", "NORMAL", "version", d.at("/config/version").asLong()))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    post(f.s(), "/api/v1/shipments/" + id + "/sync-retry", java.util.Map.of(), 200);
    worker("shipmentWorker");
    assertThat(get(f.s(), "/api/v1/shipments/" + id).at("/currentSync/state").asText())
        .isEqualTo("ACKED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from shipments where tenant_id=?", Integer.class, A))
        .isEqualTo(1);
  }

  @Test
  void timeoutAfterSubmitQueriesWithoutDuplicateAndCorrectionsKeepHistory() throws Exception {
    var f = fixture(true);
    shipment(f, "MOCK-PKG-TIMEOUT", 1, "MOCK-TRACK-A", "SUBMIT_TIMEOUT_AFTER");
    worker("shipmentWorker");
    var d = get(f.s(), "/api/v1/shipments?purchaseOrderId=" + f.purchase()).at("/items/0");
    String id = d.get("id").asText();
    assertThat(d.at("/currentSync/state").asText()).isEqualTo("RETRY_WAIT");
    jdbc.update(
        "update outbox_events set available_at=now()-interval '1 second' where tenant_id=? and"
            + " event_type='M4_SHIPMENT_SYNC'",
        A);
    worker("shipmentWorker");
    assertThat(get(f.s(), "/api/v1/shipments/" + id).at("/currentSync/state").asText())
        .isEqualTo("ACKED");
    assertThat(
            jdbc.queryForObject(
                "select sum(submit_calls) from mock_shipment_ack_authority where tenant_id=?",
                Integer.class,
                A))
        .isEqualTo(1);
    shipment(f, "MOCK-PKG-TIMEOUT", 2, "MOCK-TRACK-B", "NORMAL");
    worker("shipmentWorker");
    shipment(f, "MOCK-PKG-TIMEOUT", 3, "MOCK-TRACK-C", "NORMAL");
    worker("shipmentWorker");
    var latest = get(f.s(), "/api/v1/shipments/" + id);
    assertThat(latest.get("trackingNumber").asText()).isEqualTo("MOCK-TRACK-C");
    assertThat(latest.get("versions").size()).isEqualTo(3);
    assertThat(latest.get("syncs").size()).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select shipment_ack_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                f.item()))
        .isEqualTo(1);
  }

  @Test
  void eventIdCannotBeReusedAgainstAnotherPurchase() throws Exception {
    var a = fixture(true);
    var b = fixture(true);
    var e = shipment("MOCK-PKG-EVENT", 1, "MOCK-TRACK-EVENT", "NORMAL");
    post(a.s(), "/api/v1/mock/purchases/" + a.purchase() + "/shipment-events", e, 202);
    post(b.s(), "/api/v1/mock/purchases/" + b.purchase() + "/shipment-events", e, 409);
  }

  @Test
  void sameSourceVersionConflictingPayloadIsRejected() throws Exception {
    var f = fixture(true);
    shipment(f, "MOCK-PKG-CONFLICT", 1, "MOCK-TRACK-A", "NORMAL");
    var e = shipment("MOCK-PKG-CONFLICT", 1, "MOCK-TRACK-B", "NORMAL");
    post(f.s(), "/api/v1/mock/purchases/" + f.purchase() + "/shipment-events", e, 409);
  }

  @Test
  void deliveredSourceUpdateDoesNotCreateTrackingCorrection() throws Exception {
    var f = fixture(true);
    shipment(f, "MOCK-PKG-STATE", 1, "MOCK-TRACK-STATE", "NORMAL");
    worker("shipmentWorker");
    var e =
        new java.util.LinkedHashMap<>(shipment("MOCK-PKG-STATE", 2, "MOCK-TRACK-STATE", "NORMAL"));
    e.put("sourceState", "DELIVERED");
    post(f.s(), "/api/v1/mock/purchases/" + f.purchase() + "/shipment-events", e, 202);
    worker("shipmentWorker");
    var d = get(f.s(), "/api/v1/shipments?purchaseOrderId=" + f.purchase()).at("/items/0");
    assertThat(d.get("trackingVersion").asLong()).isEqualTo(1);
    assertThat(d.get("state").asText()).isEqualTo("DELIVERED");
    assertThat(
            jdbc.queryForObject(
                "select sum(submit_calls) from mock_shipment_ack_authority where tenant_id=?",
                Integer.class,
                A))
        .isEqualTo(1);
  }

  @Test
  void affectedAftersaleBlocksDownstreamSyncButPreservesSupplierPackage() throws Exception {
    var f = fixture(true);
    post(
        f.s(),
        "/api/v1/mock/orders/" + f.order() + "/aftersale-events",
        java.util.Map.of(
            "eventId",
            "MOCK-BLOCK-SHIP",
            "sourceCaseId",
            "MOCK-CASE-BLOCKSHIP",
            "type",
            "REFUND_ONLY",
            "sourceState",
            "REQUESTED",
            "sourceVersion",
            1,
            "salesOrderItemId",
            f.item(),
            "qty",
            1,
            "requestedRefundFen",
            4300),
        202);
    shipment(f, "MOCK-PKG-BLOCK", 1, "MOCK-TRACK-BLOCK", "NORMAL");
    worker("shipmentWorker");
    var d = get(f.s(), "/api/v1/shipments?purchaseOrderId=" + f.purchase()).at("/items/0");
    assertThat(d.get("sourceState").asText()).isEqualTo("SHIPPED");
    assertThat(d.at("/currentSync/state").asText()).isEqualTo("BLOCKED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mock_shipment_ack_authority where tenant_id=?",
                Integer.class,
                A))
        .isZero();
  }

  @Test
  void concurrentWorkersAndDuplicateEventsKeepTwoIndependentPackages() throws Exception {
    var f = fixture(true);
    var one = shipment("MOCK-PKG-CONCURRENT-A", 1, "MOCK-TRACK-CONCURRENT-A", "NORMAL");
    for (int n = 0; n < 10; n++)
      post(f.s(), "/api/v1/mock/purchases/" + f.purchase() + "/shipment-events", one, 202);
    shipment(f, "MOCK-PKG-CONCURRENT-B", 1, "MOCK-TRACK-CONCURRENT-B", "NORMAL");
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
      for (var result :
          pool.invokeAll(
              java.util.List.<java.util.concurrent.Callable<Void>>of(
                  () -> {
                    worker("shipmentWorker");
                    return null;
                  },
                  () -> {
                    worker("shipmentWorker");
                    return null;
                  },
                  () -> {
                    worker("shipmentWorker");
                    return null;
                  }))) result.get();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from shipments where tenant_id=?", Integer.class, A))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select shipment_ack_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                f.item()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select sum(submit_calls) from mock_shipment_ack_authority where tenant_id=?",
                Integer.class,
                A))
        .isEqualTo(2);
  }

  @Test
  void retriesStopAtFiveAndFailureNotificationIsDeduplicated() throws Exception {
    var f = fixture(true);
    shipment(f, "MOCK-PKG-BOUNDED", 1, "MOCK-TRACK-BOUNDED", "SUBMIT_5XX");
    for (int n = 0; n < 7; n++) {
      worker("shipmentWorker");
      jdbc.update(
          "update outbox_events set available_at=now()-interval '1 second' where tenant_id=? and"
              + " event_type='M4_SHIPMENT_SYNC' and state='RETRY_WAIT'",
          A);
    }
    assertThat(
            jdbc.queryForObject(
                "select attempts from outbox_events where tenant_id=? and"
                    + " event_type='M4_SHIPMENT_SYNC'",
                Integer.class,
                A))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "select state from outbox_events where tenant_id=? and"
                    + " event_type='M4_SHIPMENT_SYNC'",
                String.class,
                A))
        .isEqualTo("DEAD");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from notifications where tenant_id=? and"
                    + " code='SHIPMENT_SUBMIT_UNAVAILABLE'",
                Integer.class,
                A))
        .isEqualTo(2);
  }

  @Test
  void historicalUnknownVersionQueriesAndRetainsReceiptAfterCorrection() throws Exception {
    var f = fixture(true);
    shipment(f, "MOCK-PKG-HISTORY", 1, "MOCK-TRACK-HISTORY-1", "SUBMIT_TIMEOUT_AFTER");
    worker("shipmentWorker");
    shipment(f, "MOCK-PKG-HISTORY", 2, "MOCK-TRACK-HISTORY-2", "NORMAL");
    worker("shipmentWorker");
    jdbc.update(
        "update outbox_events set available_at=now()-interval '1 second' where tenant_id=? and"
            + " event_type='M4_SHIPMENT_SYNC' and state='RETRY_WAIT'",
        A);
    worker("shipmentWorker");
    String id =
        get(f.s(), "/api/v1/shipments?purchaseOrderId=" + f.purchase()).at("/items/0/id").asText();
    var d = get(f.s(), "/api/v1/shipments/" + id);
    assertThat(d.at("/syncs/0/state").asText()).isEqualTo("ACKED");
    assertThat(d.at("/syncs/0/platformAck").isNull()).isFalse();
    assertThat(d.at("/currentSync/trackingVersion").asLong()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select shipment_ack_qty from sales_order_items where tenant_id=? and id=?",
                Integer.class,
                A,
                f.item()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select sum(submit_calls) from mock_shipment_ack_authority where tenant_id=?",
                Integer.class,
                A))
        .isEqualTo(2);
  }
}
