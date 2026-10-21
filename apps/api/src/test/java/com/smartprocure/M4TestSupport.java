package com.smartprocure;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.smartprocure.sales.SalesEventWorker;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

abstract class M4TestSupport {
  static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationContext context;
  @Autowired SalesEventWorker salesWorker;

  record Session(MockHttpSession session, String csrf) {}

  record Fixture(Session s, UUID store, UUID order, UUID item, UUID other, UUID purchase) {}

  JsonNode data(org.springframework.test.web.servlet.MvcResult r) throws Exception {
    return mapper.readTree(r.getResponse().getContentAsString()).get("data");
  }

  Session login(String who) throws Exception {
    var r =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/auth/csrf"))
            .andReturn();
    var s = (MockHttpSession) r.getRequest().getSession();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/auth/login")
                .session(s)
                .header("X-CSRF-TOKEN", data(r).get("token").asText())
                .contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("persona", who))))
        .andExpect(status().isOk());
    return new Session(
        s,
        data(mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                            "/api/v1/auth/csrf")
                        .session(s))
                .andReturn())
            .get("token")
            .asText());
  }

  void clean() {
    jdbc.update("delete from m4_mock_events");
    jdbc.execute("truncate stores,supplier_accounts cascade");
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource("db/demo/m1-seed.sql"),
            new org.springframework.core.io.ClassPathResource("db/demo/m2-seed.sql"),
            new org.springframework.core.io.ClassPathResource("db/demo/m3-seed.sql"))
        .execute(jdbc.getDataSource());
  }

  JsonNode post(Session s, String path, Object body, int expected) throws Exception {
    return data(
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .session(s.session())
                    .header("X-CSRF-TOKEN", s.csrf())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(body)))
            .andExpect(status().is(expected))
            .andReturn());
  }

  Fixture fixture(boolean paid) throws Exception {
    Session s = login("OWNER_A");
    UUID store =
        UUID.fromString(
            post(
                    s,
                    "/api/v1/stores",
                    Map.of(
                        "platform",
                        "DOUYIN",
                        "externalShopId",
                        "MOCK-" + UUID.randomUUID(),
                        "displayName",
                        "M4合成店"),
                    200)
                .get("id")
                .asText());
    String ext = "MOCK-" + UUID.randomUUID();
    post(
        s,
        "/api/v1/mock/stores/" + store + "/scenarios",
        Map.of("externalOrderId", ext, "scenario", "PAID"),
        202);
    salesWorker.runOnce();
    UUID order =
        jdbc.queryForObject(
            "select id from sales_orders where tenant_id=? and external_order_id=?",
            UUID.class,
            A,
            ext);
    UUID item =
        jdbc.queryForObject(
            "select id from sales_order_items where tenant_id=? and sales_order_id=? and"
                + " external_item_id='ITEM-1'",
            UUID.class,
            A,
            order);
    UUID other =
        jdbc.queryForObject(
            "select id from sales_order_items where tenant_id=? and sales_order_id=? and"
                + " external_item_id='ITEM-2'",
            UUID.class,
            A,
            order);
    UUID account = UUID.randomUUID(),
        offer = UUID.randomUUID(),
        mapping = UUID.randomUUID(),
        intent = UUID.randomUUID(),
        purchase = UUID.randomUUID();
    jdbc.update(
        "insert into supplier_accounts(id,tenant_id,provider,external_account_id,status)"
            + " values(?,?,'MOCK_SUPPLIER',?,'ACTIVE')",
        account,
        A,
        "MOCK-" + account);
    jdbc.update(
        "insert into"
            + " supplier_offers(id,tenant_id,supplier_account_id,external_offer_id,external_sku_id,display_name)"
            + " values(?,?,?,?,?,'合成货源')",
        offer,
        A,
        account,
        "MOCK-" + offer,
        "MOCK-SKU-BLUE");
    jdbc.update(
        "insert into sku_mappings(id,tenant_id,store_id,sales_sku_id,supplier_offer_id)"
            + " values(?,?,?,'MOCK-SKU-BLUE',?)",
        mapping,
        A,
        store,
        offer);
    jdbc.update(
        "insert into"
            + " purchase_intents(id,tenant_id,sales_order_item_id,mapping_id,qty,state,estimated_total_fen,supplier_account_id,supplier_offer_id,currency)"
            + " values(?,?,?,?,2,?,2000,?,?,'CNY')",
        intent,
        A,
        item,
        mapping,
        paid ? "PAID" : "APPROVED",
        account,
        offer);
    jdbc.update(
        "insert into"
            + " purchase_orders(id,tenant_id,purchase_intent_id,supplier_account_id,external_order_id,state,total_fen)"
            + " values(?,?,?,?,?,?,2000)",
        purchase,
        A,
        intent,
        account,
        "MOCK-PURCHASE-" + purchase,
        paid ? "PAID" : "AWAITING_PAYMENT");
    if (paid)
      jdbc.update(
          "insert into"
              + " payment_attempts(tenant_id,purchase_order_id,attempt_no,mode,state,amount_fen,idempotency_key,provider_payment_reference,resolved_at,last_verified_at)"
              + " values(?,?,1,'MANUAL','PAID',2000,?,'MOCK-PAYMENT-TEST',now(),now())",
          A,
          purchase,
          UUID.randomUUID().toString());
    return new Fixture(s, store, order, item, other, purchase);
  }

  Map<String, Object> shipment(String pkg, long version, String tracking, String behavior) {
    return Map.of(
        "eventId",
        "MOCK-" + UUID.randomUUID(),
        "externalShipmentId",
        pkg,
        "sourceVersion",
        version,
        "qty",
        1,
        "carrierCode",
        "MOCK_EXPRESS",
        "trackingNumber",
        tracking,
        "sourceState",
        "SHIPPED",
        "behavior",
        behavior);
  }

  JsonNode shipment(Fixture f, String pkg, long version, String tracking, String behavior)
      throws Exception {
    return post(
        f.s(),
        "/api/v1/mock/purchases/" + f.purchase() + "/shipment-events",
        shipment(pkg, version, tracking, behavior),
        202);
  }

  void worker(String bean) throws Exception {
    context.getBean(bean).getClass().getMethod("runOnce").invoke(context.getBean(bean));
  }

  JsonNode get(Session s, String path) throws Exception {
    return data(
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .session(s.session()))
            .andExpect(status().isOk())
            .andReturn());
  }
}
