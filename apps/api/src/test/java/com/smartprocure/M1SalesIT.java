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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.schemas=m1_test", "spring.flyway.default-schema=m1_test", "spring.datasource.hikari.schema=m1_test", "app.mock.worker-enabled=false", "app.mock.webhook-secret=MOCK_ONLY_M1_TEST_SIGNING_KEY"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M0_TEST_DB_URL", matches = ".+")
class M1SalesIT {
    static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SalesEventWorker worker;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv("M0_TEST_DB_URL"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("M0_TEST_DB_USER", "postgres"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("M0_TEST_DB_PASSWORD", ""));
    }
    record Session(MockHttpSession session, String csrf) { }
    Session login(String persona) throws Exception {
        var first = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        var session = (MockHttpSession)first.getRequest().getSession();
        String token = mapper.readTree(first.getResponse().getContentAsString()).at("/data/token").asText();
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content(mapper.writeValueAsString(Map.of("persona",persona)))).andExpect(status().isOk());
        var result = mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        return new Session(session,mapper.readTree(result.getResponse().getContentAsString()).at("/data/token").asText());
    }
    JsonNode data(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsString()).get("data"); }
    JsonNode store(Session session) throws Exception {
        return data(mvc.perform(post("/api/v1/stores").session(session.session()).header("X-CSRF-TOKEN",session.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("platform","DOUYIN","externalShopId","MOCK-"+UUID.randomUUID(),"displayName","合成测试店铺")))).andExpect(status().isOk()).andReturn());
    }
    String orderId() { return "MOCK-"+UUID.randomUUID(); }
    JsonNode scenario(Session s, String store, String external, String scenario, String key) throws Exception {
        return data(mvc.perform(post("/api/v1/mock/stores/"+store+"/scenarios").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(Map.of("externalOrderId",external,"scenario",scenario)))).andExpect(status().isAccepted()).andReturn());
    }
    String signature(String integration, String timestamp, String nonce, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("MOCK_ONLY_M1_TEST_SIGNING_KEY".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp+"."+nonce+"."+integration+"."+body).getBytes(StandardCharsets.UTF_8)));
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder callback(String integration,String event,String external,long version,String timestamp,String nonce) throws Exception {
        String body=mapper.writeValueAsString(Map.of("eventId",event,"externalOrderId",external,"sourceVersion",version,"eventOccurredAt",Instant.now().toString()));
        return post("/api/v1/webhooks/mock/stores/"+integration).header("X-Mock-Timestamp",timestamp).header("X-Mock-Nonce",nonce).header("X-Mock-Signature",signature(integration,timestamp,nonce,body)).contentType("application/json").content(body);
    }
    @Test void t001TenDuplicatesHaveOneOrderAndNoProcurement() throws Exception {
        Session s=login("OWNER_A"); JsonNode store=store(s); String external=orderId();
        scenario(s,store.get("id").asText(),external,"DUPLICATE",UUID.randomUUID().toString());
        worker.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from sales_orders where tenant_id=? and external_order_id=?",Integer.class,A,external)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from inbox_events where tenant_id=? and external_order_id=?",Integer.class,A,external)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from purchase_orders",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_attempts",Integer.class)).isZero();
    }
    @Test void t002SameExternalOrderAcrossTenantsIsStoredAndInvisibleAcrossIds() throws Exception {
        Session a=login("OWNER_A"),b=login("OWNER_B"); JsonNode sa=store(a),sb=store(b); String external=orderId();
        scenario(a,sa.get("id").asText(),external,"PAID",UUID.randomUUID().toString());
        scenario(b,sb.get("id").asText(),external,"PAID",UUID.randomUUID().toString()); worker.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from sales_orders where external_order_id=?",Integer.class,external)).isEqualTo(2);
        String bid=jdbc.queryForObject("select id::text from sales_orders where tenant_id=? and external_order_id=?",String.class,B,external);
        mvc.perform(get("/api/v1/orders/"+bid).session(a.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores/"+sb.get("id").asText()).session(a.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders").queryParam("storeId",sb.get("id").asText()).session(a.session())).andExpect(status().isNotFound());
    }
    @Test void outOfOrderAndDelayedEventsCannotRollBackAuthorityVersion() throws Exception {
        Session s=login("OWNER_A"); JsonNode store=store(s); String external=orderId();
        scenario(s,store.get("id").asText(),external,"OUT_OF_ORDER",UUID.randomUUID().toString()); worker.runOnce();
        assertThat(jdbc.queryForObject("select source_status from sales_orders where tenant_id=? and external_order_id=?",String.class,A,external)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select source_version from sales_orders where tenant_id=? and external_order_id=?",Long.class,A,external)).isEqualTo(2);
        String delayed=orderId(); scenario(s,store.get("id").asText(),delayed,"DELAYED",UUID.randomUUID().toString()); worker.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from sales_orders where external_order_id=?",Integer.class,delayed)).isZero();
        jdbc.update("update outbox_events set available_at=now()-interval '1 second' where tenant_id=? and state='NEW'",A); worker.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from sales_orders where external_order_id=?",Integer.class,delayed)).isEqualTo(1);
    }
    @Test void t010RefundAuthoritySuspendsOrderWithZeroPurchaseWritesM1Boundary() throws Exception {
        Session s=login("OWNER_A"); JsonNode store=store(s); String external=orderId();
        scenario(s,store.get("id").asText(),external,"REFUNDED",UUID.randomUUID().toString()); worker.runOnce();
        assertThat(jdbc.queryForObject("select local_status from sales_orders where tenant_id=? and external_order_id=?",String.class,A,external)).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("select sum(cancelled_qty) from sales_order_items i join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where o.external_order_id=?",Integer.class,external)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from purchase_orders",Integer.class)).isZero();
    }
    @Test void t014SupportPaymentPolicyDeniedWithSafeAuditM1Boundary() throws Exception {
        Session s=login("SUPPORT_A"); String id=UUID.randomUUID().toString();
        mvc.perform(put("/api/v1/payment-policy").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("X-Request-Id",id).contentType("application/json").content("{\"mode\":\"AUTO\",\"credential\":\"DO_NOT_LOG_SYNTHETIC\"}")).andExpect(status().isForbidden());
        String details=jdbc.queryForObject("select redacted_details::text from audit_logs where request_id=?",String.class,id);
        assertThat(details).contains("DENIED").doesNotContain("DO_NOT_LOG_SYNTHETIC","credential","token");
    }
    @Test void t015UnimplementedPurchaseReadDoesNotRevealContentM1Boundary() throws Exception {
        Session s=login("OWNER_A");
        mvc.perform(get("/api/v1/purchases/"+UUID.randomUUID()).session(s.session())).andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void t016InvalidSignatureExpiredTimestampNonceReuseAndCrossIntegrationReplayFail() throws Exception {
        Session s=login("OWNER_A"); JsonNode st=store(s),other=store(s); String integration=st.get("integrationId").asText(); String external=orderId();
        String ts=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/webhooks/mock/stores/"+integration).header("X-Mock-Timestamp",ts).header("X-Mock-Nonce",nonce).header("X-Mock-Signature","00".repeat(32)).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(callback(integration,"e-"+UUID.randomUUID(),external,1,Long.toString(Instant.now().minusSeconds(301).getEpochSecond()),UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        String event="e-"+UUID.randomUUID(); var valid=callback(integration,event,external,1,ts,nonce); mvc.perform(valid).andExpect(status().isOk());
        mvc.perform(callback(integration,"e-"+UUID.randomUUID(),external,1,ts,nonce)).andExpect(status().isConflict());
        String raw=mapper.writeValueAsString(Map.of("eventId","e-"+UUID.randomUUID(),"externalOrderId",external,"sourceVersion",1,"eventOccurredAt",Instant.now().toString()));
        String crossNonce=UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/webhooks/mock/stores/"+other.get("integrationId").asText()).header("X-Mock-Timestamp",ts).header("X-Mock-Nonce",crossNonce).header("X-Mock-Signature",signature(integration,ts,crossNonce,raw)).contentType("application/json").content(raw)).andExpect(status().isUnauthorized());
        mvc.perform(valid).andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicate").value(true));
        assertThat(jdbc.queryForObject("select count(*) from sales_orders where external_order_id=?",Integer.class,external)).isZero();
    }
    @Test void supportCanReadOnlyAssignedStoreAndCannotMutateStoresOrSimulate() throws Exception {
        Session support=login("SUPPORT_A"),owner=login("OWNER_A"); JsonNode extra=store(owner);
        JsonNode list=data(mvc.perform(get("/api/v1/stores").session(support.session())).andExpect(status().isOk()).andReturn());
        assertThat(list.get("total").asInt()).isEqualTo(1);
        mvc.perform(get("/api/v1/stores/"+extra.get("id").asText()).session(support.session())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/stores").session(support.session()).header("X-CSRF-TOKEN",support.csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/mock/stores/"+extra.get("id").asText()+"/scenarios").session(support.session()).header("X-CSRF-TOKEN",support.csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void storeVersionConflictAndDisabledOrExpiredAuthorizationStopPendingWorker() throws Exception {
        Session s=login("OWNER_A"); JsonNode st=store(s); String id=st.get("id").asText(),external=orderId();
        scenario(s,id,external,"PAID",UUID.randomUUID().toString());
        mvc.perform(patch("/api/v1/stores/"+id).session(s.session()).header("X-CSRF-TOKEN",s.csrf()).contentType("application/json").content("{\"version\":0,\"authStatus\":\"REVOKED\"}")).andExpect(status().isOk());
        worker.runOnce(); assertThat(jdbc.queryForObject("select count(*) from sales_orders where external_order_id=?",Integer.class,external)).isZero();
        mvc.perform(patch("/api/v1/stores/"+id).session(s.session()).header("X-CSRF-TOKEN",s.csrf()).contentType("application/json").content("{\"version\":0,\"enabled\":false}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/mock/stores/"+id+"/scenarios").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("externalOrderId",orderId(),"scenario","PAID")))).andExpect(status().isConflict());
    }
    @Test void commandKeyReuseReturnsOriginalAndRejectsChangedPayload() throws Exception {
        Session s=login("OWNER_A"); JsonNode st=store(s); String key=UUID.randomUUID().toString(),external=orderId();
        JsonNode first=scenario(s,st.get("id").asText(),external,"PAID",key);
        assertThat(scenario(s,st.get("id").asText(),external,"PAID",key)).isEqualTo(first);
        mvc.perform(post("/api/v1/mock/stores/"+st.get("id").asText()+"/scenarios").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(Map.of("externalOrderId",external,"scenario","REFUNDED")))).andExpect(status().isConflict());
    }
    @Test void invalidPaginationUnknownTenantAndUnboundedInputsFailClosed() throws Exception {
        Session s=login("OWNER_A");
        for(String query:new String[]{"limit=101","limit=0","offset=10001","sourceStatus=BAD","from=invalid"}) mvc.perform(get("/api/v1/orders?"+query).session(s.session())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/stores").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"tenantId\":\""+B+"\",\"platform\":\"DOUYIN\",\"externalShopId\":\"MOCK-injected\",\"displayName\":\"test\"}")).andExpect(status().isBadRequest());
    }
    @Test void t021ConcurrentIndependentTenantSessionsNeverMixRowsAndCanReauthenticate() throws Exception {
        Session a=login("OWNER_A"),b=login("OWNER_B"); JsonNode sa=store(a),sb=store(b);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var tasks=java.util.stream.IntStream.range(0,20).mapToObj(i->(java.util.concurrent.Callable<Void>)()->{
                boolean alice=i%2==0; Session s=alice?a:b; String forbidden=alice?sb.get("id").asText():sa.get("id").asText();
                mvc.perform(get("/api/v1/stores/"+forbidden).session(s.session()).header("X-Tenant-Id",alice?B:A)).andExpect(status().isNotFound());
                mvc.perform(get("/api/v1/me").session(s.session())).andExpect(jsonPath("$.data.currentTenantId").value((alice?A:B).toString())); return null;
            }).toList(); for(var future:pool.invokeAll(tasks)) future.get();
        }
        Session switched=login("OWNER_B"); mvc.perform(get("/api/v1/stores/"+sa.get("id").asText()).session(switched.session())).andExpect(status().isNotFound());
    }
    void retryUntilDead(String external) {
        for(int i=0;i<5;i++) { jdbc.update("update outbox_events o set available_at=now()-interval '1 second' from inbox_events i where i.tenant_id=o.tenant_id and i.id=o.inbox_id and i.external_order_id=? and o.state='RETRY_WAIT'",external);worker.runOnce(); }
    }
    @Test void authoritativeSameVersionDifferentContentIsFrozenAndNeverOverwritesOrder() throws Exception {
        Session s=login("OWNER_A");JsonNode st=store(s);String external=orderId();scenario(s,st.get("id").asText(),external,"PAID",UUID.randomUUID().toString());worker.runOnce();
        jdbc.update("update mock_order_authority set snapshot=jsonb_set(jsonb_set(snapshot,'{paidAmountFen}','13000'),'{items,1,paidAllocatedFen}','4400') where tenant_id=? and store_id=? and external_order_id=?",A,UUID.fromString(st.get("id").asText()),external);
        mvc.perform(callback(st.get("integrationId").asText(),"e-"+UUID.randomUUID(),external,1,Long.toString(Instant.now().getEpochSecond()),UUID.randomUUID().toString())).andExpect(status().isOk());
        retryUntilDead(external);
        assertThat(jdbc.queryForObject("select paid_amount_fen from sales_orders where tenant_id=? and external_order_id=?",Long.class,A,external)).isEqualTo(12900);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events o join inbox_events i on i.tenant_id=o.tenant_id and i.id=o.inbox_id where i.external_order_id=? and o.state='DEAD' and o.last_error_code='AUTHORITY_VERSION_CONFLICT' and o.attempts=5",Integer.class,external)).isEqualTo(1);
    }
    @Test void unavailableAuthorityRetriesAtMostFiveTimesAndUsesSafeDeadLetterCode() throws Exception {
        Session s=login("OWNER_A");JsonNode st=store(s);String external=orderId();
        mvc.perform(callback(st.get("integrationId").asText(),"e-"+UUID.randomUUID(),external,1,Long.toString(Instant.now().getEpochSecond()),UUID.randomUUID().toString())).andExpect(status().isOk());
        retryUntilDead(external);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events o join inbox_events i on i.tenant_id=o.tenant_id and i.id=o.inbox_id where i.external_order_id=? and o.state='DEAD' and o.attempts=5 and o.last_error_code='AUTHORITY_NOT_FOUND'",Integer.class,external)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sales_orders where external_order_id=?",Integer.class,external)).isZero();
    }
    @Test void abandonedWorkerLeaseCanBeRecoveredWithoutDuplicateOrderOrTimeline() throws Exception {
        Session s=login("OWNER_A");JsonNode st=store(s);String external=orderId();scenario(s,st.get("id").asText(),external,"PAID",UUID.randomUUID().toString());
        jdbc.update("update outbox_events o set state='PROCESSING',lease_until=now()-interval '1 second',lease_token=?,attempts=1 from inbox_events i where i.tenant_id=o.tenant_id and i.id=o.inbox_id and i.external_order_id=?",UUID.randomUUID(),external);
        worker.runOnce();worker.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from sales_orders where external_order_id=?",Integer.class,external)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sales_order_timeline t join sales_orders o on o.tenant_id=t.tenant_id and o.id=t.sales_order_id where o.external_order_id=?",Integer.class,external)).isEqualTo(1);
    }
    @Test void naturalExpiryAndDisableRejectNewSignedCallbacksAndSimulations() throws Exception {
        Session s=login("OWNER_A");JsonNode st=store(s);UUID store=UUID.fromString(st.get("id").asText());
        jdbc.update("update stores set authorization_expires_at=now()-interval '1 second' where tenant_id=? and id=?",A,store);
        mvc.perform(get("/api/v1/stores/"+store).session(s.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.authStatus").value("EXPIRED"));
        mvc.perform(callback(st.get("integrationId").asText(),"e-"+UUID.randomUUID(),orderId(),1,Long.toString(Instant.now().getEpochSecond()),UUID.randomUUID().toString())).andExpect(status().isConflict());
        jdbc.update("update stores set authorization_expires_at=now()+interval '1 day',enabled=false where tenant_id=? and id=?",A,store);
        mvc.perform(callback(st.get("integrationId").asText(),"e-"+UUID.randomUUID(),orderId(),1,Long.toString(Instant.now().getEpochSecond()),UUID.randomUUID().toString())).andExpect(status().isConflict());
    }
    @Test void suspendedTenantOrRealFlagBlocksAlreadyQueuedWork() throws Exception {
        Session s=login("OWNER_B");JsonNode st=store(s);String frozen=orderId();scenario(s,st.get("id").asText(),frozen,"PAID",UUID.randomUUID().toString());
        jdbc.update("update tenants set status='SUSPENDED' where id=?",B);
        try { worker.runOnce();assertThat(jdbc.queryForObject("select count(*) from sales_orders where external_order_id=?",Integer.class,frozen)).isZero(); }
        finally { jdbc.update("update tenants set status='ACTIVE' where id=?",B); }
        String real=orderId();scenario(s,st.get("id").asText(),real,"PAID",UUID.randomUUID().toString());
        jdbc.update("insert into feature_flags(flag_name,enabled) values('real_m1_worker_test',true)");
        try { worker.runOnce();assertThat(jdbc.queryForObject("select count(*) from sales_orders where external_order_id=?",Integer.class,real)).isZero(); }
        finally { jdbc.update("delete from feature_flags where flag_name='real_m1_worker_test'"); }
        assertThat(jdbc.queryForObject("select count(*) from outbox_events o join inbox_events i on i.tenant_id=o.tenant_id and i.id=o.inbox_id where i.external_order_id in (?,?) and o.state='BLOCKED'",Integer.class,frozen,real)).isEqualTo(2);
    }
    @Test void searchStatusPaginationDetailAmountsAndEquivalentTimezoneFilters() throws Exception {
        Session s=login("OWNER_A");JsonNode st=store(s);String external=orderId();scenario(s,st.get("id").asText(),external,"PAID",UUID.randomUUID().toString());worker.runOnce();
        JsonNode list=data(mvc.perform(get("/api/v1/orders").session(s.session()).queryParam("q",external).queryParam("sourceStatus","PAID").queryParam("storeId",st.get("id").asText()).queryParam("limit","1")).andExpect(status().isOk()).andReturn());
        assertThat(list.get("total").asInt()).isEqualTo(1);JsonNode order=list.get("items").get(0);
        JsonNode detail=data(mvc.perform(get("/api/v1/orders/"+order.get("id").asText()).session(s.session())).andExpect(status().isOk()).andReturn());
        assertThat(detail.get("items").size()).isEqualTo(2);assertThat(detail.get("timeline").size()).isEqualTo(1);
        long allocated=0;for(JsonNode item:detail.get("items"))allocated+=item.get("paidAllocatedFen").asLong();assertThat(allocated).isEqualTo(detail.get("paidAmountFen").asLong());
        Instant timestamp=Instant.parse(order.get("sourceUpdatedAt").asText());
        String utc=timestamp.minusSeconds(1).toString(),shanghai=timestamp.minusSeconds(1).atOffset(java.time.ZoneOffset.ofHours(8)).toString();
        JsonNode utcList=data(mvc.perform(get("/api/v1/orders").session(s.session()).queryParam("q",external).queryParam("from",utc)).andExpect(status().isOk()).andReturn());
        JsonNode localList=data(mvc.perform(get("/api/v1/orders").session(s.session()).queryParam("q",external).queryParam("from",shanghai)).andExpect(status().isOk()).andReturn());assertThat(localList).isEqualTo(utcList);
        mvc.perform(get("/api/v1/orders").session(s.session()).queryParam("q",external).queryParam("to",timestamp.minusSeconds(1).toString())).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/orders").session(s.session()).queryParam("q",external).queryParam("offset","1")).andExpect(jsonPath("$.data.items").isEmpty());
    }
    @Test void signedNullUnknownFieldsOversizedBodyAndChangedEventPayloadAreRejected() throws Exception {
        Session s=login("OWNER_A");JsonNode st=store(s);String integration=st.get("integrationId").asText(),ts=Long.toString(Instant.now().getEpochSecond());
        for(String raw:new String[]{"null","{\"tenantId\":\""+B+"\"}"," ".repeat(4097)}) {
            String nonce=UUID.randomUUID().toString();mvc.perform(post("/api/v1/webhooks/mock/stores/"+integration).header("X-Mock-Timestamp",ts).header("X-Mock-Nonce",nonce).header("X-Mock-Signature",signature(integration,ts,nonce,raw)).contentType("application/json").content(raw)).andExpect(status().isBadRequest());
        }
        String event="e-"+UUID.randomUUID();mvc.perform(callback(integration,event,orderId(),1,ts,UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(callback(integration,event,orderId(),2,ts,UUID.randomUUID().toString())).andExpect(status().isConflict());
    }
    @Test void storeAuditHasOnlySafeStateSummaryAndDetailTimelineIsBounded() throws Exception {
        Session s=login("OWNER_A");JsonNode st=store(s);String external=orderId();scenario(s,st.get("id").asText(),external,"PAID",UUID.randomUUID().toString());worker.runOnce();
        String audit=jdbc.queryForObject("select redacted_details::text from audit_logs where tenant_id=? and entity_id=? and action='MOCK_STORE_REGISTERED'",String.class,A,UUID.fromString(st.get("id").asText()));
        assertThat(mapper.readTree(audit).fieldNames()).toIterable().containsExactlyInAnyOrder("version","enabled","authStatus");
        UUID order=jdbc.queryForObject("select id from sales_orders where tenant_id=? and external_order_id=?",UUID.class,A,external);
        for(int i=0;i<105;i++)jdbc.update("insert into sales_order_timeline(tenant_id,sales_order_id,event_id,action,source_version) values(?,?,?,'AUTHORITY_UNCHANGED',1)",A,order,"BOUND-"+UUID.randomUUID());
        mvc.perform(get("/api/v1/orders/"+order).session(s.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.timeline.length()").value(100));
    }
    @Test void exhaustedCrashedWorkerLeaseGoesDeadWithoutSixthProviderAttempt() throws Exception {
        Session s=login("OWNER_A");JsonNode st=store(s);String external=orderId();scenario(s,st.get("id").asText(),external,"PAID",UUID.randomUUID().toString());
        jdbc.update("update outbox_events o set state='PROCESSING',lease_until=now()-interval '1 second',lease_token=?,attempts=5 from inbox_events i where i.tenant_id=o.tenant_id and i.id=o.inbox_id and i.external_order_id=?",UUID.randomUUID(),external);
        worker.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events o join inbox_events i on i.tenant_id=o.tenant_id and i.id=o.inbox_id where i.external_order_id=? and o.state='DEAD' and o.attempts=5 and o.last_error_code='WORKER_LEASE_EXHAUSTED'",Integer.class,external)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sales_orders where external_order_id=?",Integer.class,external)).isZero();
    }
}
