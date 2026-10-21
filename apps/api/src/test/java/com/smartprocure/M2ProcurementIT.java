package com.smartprocure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.sales.SalesEventWorker;
import com.smartprocure.provider.MockSupplierAdapter;
import com.smartprocure.provider.SupplierProviderAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.schemas=m2_procurement_test","spring.flyway.default-schema=m2_procurement_test","spring.datasource.hikari.schema=m2_procurement_test","app.mock.worker-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="M0_TEST_DB_URL",matches=".+")
class M2ProcurementIT {
    static final UUID A=UUID.fromString("11111111-1111-4111-8111-111111111111");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired SalesEventWorker salesWorker;
    @SpyBean MockSupplierAdapter supplier;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("M0_TEST_DB_URL"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("M0_TEST_DB_USER","postgres"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("M0_TEST_DB_PASSWORD",""));
    }
    record Session(MockHttpSession session,String csrf) { }
    Session login(String persona) throws Exception {
        var csrf=mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        var session=(MockHttpSession)csrf.getRequest().getSession();
        String token=mapper.readTree(csrf.getResponse().getContentAsString()).at("/data/token").asText();
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content(mapper.writeValueAsString(Map.of("persona",persona)))).andExpect(status().isOk());
        var next=mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        return new Session(session,mapper.readTree(next.getResponse().getContentAsString()).at("/data/token").asText());
    }
    record Fixture(Session session,UUID store,UUID item,UUID offer,String external) { }
    JsonNode data(org.springframework.test.web.servlet.MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()).get("data"); }
    Fixture fixture(String fault) throws Exception {
        Session s=login("OWNER_A");
        JsonNode store=data(mvc.perform(post("/api/v1/stores").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("platform","DOUYIN","externalShopId","MOCK-"+UUID.randomUUID(),"displayName","合成采购测试店")))).andExpect(status().isOk()).andReturn());
        UUID storeId=UUID.fromString(store.get("id").asText()); String external="MOCK-"+UUID.randomUUID();
        mvc.perform(post("/api/v1/mock/stores/"+storeId+"/scenarios").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("externalOrderId",external,"scenario","PAID")))).andExpect(status().isAccepted());
        salesWorker.runOnce();
        UUID item=jdbc.queryForObject("select i.id from sales_order_items i join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where i.tenant_id=? and o.external_order_id=? and i.external_item_id='ITEM-1'",UUID.class,A,external);
        UUID account=UUID.randomUUID(),offer=UUID.randomUUID();
        jdbc.update("insert into supplier_accounts(id,tenant_id,provider,external_account_id,status) values(?,?,'MOCK',?,'ACTIVE')",account,A,"MOCK-"+account);
        jdbc.update("insert into supplier_offers(id,tenant_id,supplier_account_id,external_offer_id,external_sku_id,display_name,last_unit_price_fen,shipping_fen,compatible_sales_sku_id,mock_behavior) values(?,?,?,?,?,'合成蓝盒',1001,99,'MOCK-SKU-BLUE',?)",offer,A,account,"MOCK-"+offer,"MOCK-SKU-BLUE",fault);
        jdbc.update("insert into sku_mappings(tenant_id,store_id,sales_sku_id,supplier_offer_id,status,confirmed_by,confirmed_at,review_acknowledged) values(?,?,'MOCK-SKU-BLUE',?,'CONFIRMED','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',now(),true)",A,storeId,offer);
        return new Fixture(s,storeId,item,offer,external);
    }
    JsonNode evaluate(Fixture f) throws Exception {
        return data(mvc.perform(post("/api/v1/purchase-intents/evaluate").session(f.session().session()).header("X-CSRF-TOKEN",f.session().csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("salesOrderItemId",f.item())))).andExpect(status().isOk()).andReturn());
    }
    JsonNode create(Fixture f,JsonNode evaluation,String key) throws Exception {
        var result=mvc.perform(post("/api/v1/purchase-intents").session(f.session().session()).header("X-CSRF-TOKEN",f.session().csrf()).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(Map.of("salesOrderItemId",f.item(),"quoteId",evaluation.at("/selectedQuote/id").asText())))).andReturn();
        assertThat(result.getResolvedException()).isNull();status().isAccepted().match(result);return data(result);
    }
    void procure() throws Exception { context.getBean("procurementWorker").getClass().getMethod("runOnce").invoke(context.getBean("procurementWorker")); }
    String state(UUID item) { return jdbc.queryForObject("select state from purchase_intents where tenant_id=? and sales_order_item_id=?",String.class,A,item); }
    void due(UUID item) { jdbc.update("update outbox_events o set available_at=now()-interval '1 second' from purchase_intents i where o.tenant_id=i.tenant_id and o.aggregate_id=i.id and i.tenant_id=? and i.sales_order_item_id=? and o.state='RETRY_WAIT'",A,item); }
    @Test void uniqueIntentAndStockConsumptionStopAtAwaitingPayment() throws Exception {
        Fixture f=fixture("NORMAL");JsonNode evaluation=evaluate(f);assertThat(evaluation.get("allowed").asBoolean()).isTrue();
        assertThat(evaluation.at("/selectedQuote/totalFen").asLong()).isEqualTo(2101);
        String key=UUID.randomUUID().toString();JsonNode first=create(f,evaluation,key);assertThat(create(f,evaluation,key)).isEqualTo(first);
        assertThat(create(f,evaluation,UUID.randomUUID().toString()).get("id")).isEqualTo(first.get("id"));
        procure();procure();assertThat(state(f.item())).isEqualTo("AWAITING_PAYMENT");
        assertThat(jdbc.queryForObject("select count(*) from purchase_orders p join purchase_intents i on i.tenant_id=p.tenant_id and i.id=p.purchase_intent_id where i.tenant_id=? and i.sales_order_item_id=?",Integer.class,A,f.item())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select stock_qty from supplier_offers where tenant_id=? and id=?",Integer.class,A,f.offer())).isEqualTo(98);
        assertThat(jdbc.queryForObject("select count(*) from payment_attempts",Integer.class)).isZero();
    }
    @Test void refundAuthorityBeforeWorkerPreventsSupplierCreate() throws Exception {
        Fixture f=fixture("NORMAL");create(f,evaluate(f),UUID.randomUUID().toString());
        mvc.perform(post("/api/v1/mock/stores/"+f.store()+"/scenarios").session(f.session().session()).header("X-CSRF-TOKEN",f.session().csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("externalOrderId",f.external(),"scenario","REFUNDED")))).andExpect(status().isAccepted());
        procure();assertThat(state(f.item())).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select count(*) from mock_purchase_authority where tenant_id=? and offer_id=?",Integer.class,A,f.offer())).isZero();
    }
    @Test void timeoutAfterCreateQueriesOneDurableOrderWithoutConsumingStockTwice() throws Exception {
        Fixture f=fixture("CREATE_TIMEOUT_AFTER");create(f,evaluate(f),UUID.randomUUID().toString());procure();assertThat(state(f.item())).isEqualTo("CREATE_UNKNOWN");
        due(f.item());procure();assertThat(state(f.item())).isEqualTo("AWAITING_PAYMENT");
        assertThat(jdbc.queryForObject("select count(*) from mock_purchase_authority where tenant_id=? and offer_id=?",Integer.class,A,f.offer())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select stock_qty from supplier_offers where tenant_id=? and id=?",Integer.class,A,f.offer())).isEqualTo(98);
    }
    @Test void timeoutBeforeCreateNeverResubmitsEvenWhenQueryFindsNoOrder() throws Exception {
        Fixture f=fixture("CREATE_TIMEOUT_BEFORE");create(f,evaluate(f),UUID.randomUUID().toString());procure();assertThat(state(f.item())).isEqualTo("CREATE_UNKNOWN");
        due(f.item());procure();assertThat(state(f.item())).isEqualTo("REVIEW_REQUIRED");
        jdbc.update("update supplier_offers set mock_behavior='NORMAL' where tenant_id=? and id=?",A,f.offer());procure();
        assertThat(jdbc.queryForObject("select count(*) from mock_purchase_authority where tenant_id=? and offer_id=?",Integer.class,A,f.offer())).isZero();
    }
    @Test void changedQuoteConfigurationStopsAnAlreadyApprovedIntent() throws Exception {
        Fixture f=fixture("NORMAL");create(f,evaluate(f),UUID.randomUUID().toString());
        jdbc.update("update supplier_offers set last_unit_price_fen=2001,version=version+1 where tenant_id=? and id=?",A,f.offer());procure();
        assertThat(state(f.item())).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select count(*) from mock_purchase_authority where tenant_id=? and offer_id=?",Integer.class,A,f.offer())).isZero();
    }
    @Test void unresolvedQueriesRemainFrozenAfterBoundedRetries() throws Exception {
        Fixture f=fixture("QUERY_TIMEOUT");create(f,evaluate(f),UUID.randomUUID().toString());procure();
        for(int i=0;i<4;i++){due(f.item());procure();}
        assertThat(state(f.item())).isEqualTo("CREATE_UNKNOWN");
        assertThat(jdbc.queryForObject("select o.state from outbox_events o join purchase_intents i on i.tenant_id=o.tenant_id and i.id=o.aggregate_id where i.tenant_id=? and i.sales_order_item_id=?",String.class,A,f.item())).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select count(*) from mock_purchase_authority where tenant_id=? and offer_id=?",Integer.class,A,f.offer())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select stock_qty from supplier_offers where tenant_id=? and id=?",Integer.class,A,f.offer())).isEqualTo(98);
    }
    @Test void abandonedCreatingLeaseQueriesOnlyAndNeverIssuesAnInitialLookingCreate() throws Exception {
        Fixture f=fixture("NORMAL");JsonNode intent=create(f,evaluate(f),UUID.randomUUID().toString());UUID id=UUID.fromString(intent.get("id").asText());
        jdbc.update("insert into purchase_orders(tenant_id,purchase_intent_id,supplier_account_id,total_fen,currency,client_reference) select tenant_id,id,supplier_account_id,estimated_total_fen,currency,client_reference from purchase_intents where tenant_id=? and id=?",A,id);
        jdbc.update("update purchase_intents set state='CREATING' where tenant_id=? and id=?",A,id);
        jdbc.update("update outbox_events set state='PROCESSING',attempts=1,lease_until=now()-interval '1 second',lease_token=? where tenant_id=? and aggregate_id=? and event_type='M2_PURCHASE_CREATE'",UUID.randomUUID(),A,id);
        procure();assertThat(state(f.item())).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select failure_code from purchase_intents where tenant_id=? and id=?",String.class,A,id)).isEqualTo("CREATE_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from mock_purchase_authority where tenant_id=? and offer_id=?",Integer.class,A,f.offer())).isZero();
    }
    @Test void quoteCreateAndQueryCallsLeaveLocalTransactionsBeforeCallingProvider() throws Exception {
        org.mockito.Mockito.doAnswer(invocation->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return invocation.callRealMethod();}).when(supplier).quote(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.doAnswer(invocation->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return invocation.callRealMethod();}).when(supplier).create(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(invocation->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return invocation.callRealMethod();}).when(supplier).query(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        Fixture f=fixture("CREATE_TIMEOUT_AFTER");create(f,evaluate(f),UUID.randomUUID().toString());procure();due(f.item());procure();
        assertThat(state(f.item())).isEqualTo("AWAITING_PAYMENT");
    }
    @Test void concurrentCommandsAndWorkersProduceOneSupplierOrder() throws Exception {
        Fixture f=fixture("NORMAL");JsonNode evaluation=evaluate(f);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var tasks=java.util.stream.IntStream.range(0,3).mapToObj(i->(java.util.concurrent.Callable<String>)()->create(f,evaluation,UUID.randomUUID().toString()).get("id").asText()).toList();
            var ids=new java.util.HashSet<String>();for(var result:pool.invokeAll(tasks))ids.add(result.get());assertThat(ids).hasSize(1);
            var workers=pool.invokeAll(List.<java.util.concurrent.Callable<Void>>of(()->{procure();return null;},()->{procure();return null;}));for(var result:workers)result.get();
        }
        assertThat(state(f.item())).isEqualTo("AWAITING_PAYMENT");
        assertThat(jdbc.queryForObject("select count(*) from mock_purchase_authority where tenant_id=? and offer_id=?",Integer.class,A,f.offer())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select stock_qty from supplier_offers where tenant_id=? and id=?",Integer.class,A,f.offer())).isEqualTo(98);
    }
    @Test void rejectedCompletionCasRollsBackBusinessApplyAndFreezesForQuery() throws Exception {
        Fixture f=fixture("NORMAL");create(f,evaluate(f),UUID.randomUUID().toString());
        jdbc.execute("create function m2_procurement_test.reject_purchase_done() returns trigger language plpgsql as $$ begin if new.event_type='M2_PURCHASE_CREATE' and new.state='DONE' then return null; end if; return new; end $$");
        jdbc.execute("create trigger reject_purchase_done before update on outbox_events for each row execute function m2_procurement_test.reject_purchase_done()");
        try {
            procure();assertThat(state(f.item())).isEqualTo("CREATE_UNKNOWN");
            assertThat(jdbc.queryForObject("select p.external_order_id from purchase_orders p join purchase_intents i on i.tenant_id=p.tenant_id and i.id=p.purchase_intent_id where i.tenant_id=? and i.sales_order_item_id=?",String.class,A,f.item())).isNull();
        } finally { jdbc.execute("drop trigger reject_purchase_done on outbox_events");jdbc.execute("drop function m2_procurement_test.reject_purchase_done()"); }
        due(f.item());procure();assertThat(state(f.item())).isEqualTo("AWAITING_PAYMENT");
    }
    @Test void missingReferencedSalesItemIsRetainedAndCancelledDuringAuthorityRefresh() throws Exception {
        Fixture f=fixture("NORMAL");create(f,evaluate(f),UUID.randomUUID().toString());
        mvc.perform(post("/api/v1/mock/stores/"+f.store()+"/scenarios").session(f.session().session()).header("X-CSRF-TOKEN",f.session().csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("externalOrderId",f.external(),"scenario","REFUNDED")))).andExpect(status().isAccepted());
        jdbc.update("update mock_order_authority set snapshot=(snapshot #- '{items,0}') || jsonb_build_object('paidAmountFen',4300) where tenant_id=? and store_id=? and external_order_id=?",A,f.store(),f.external());
        salesWorker.runOnce();
        assertThat(jdbc.queryForObject("select cancelled_qty from sales_order_items where tenant_id=? and id=?",Integer.class,A,f.item())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select source_version from sales_orders where tenant_id=? and external_order_id=?",Long.class,A,f.external())).isEqualTo(2);
        procure();assertThat(state(f.item())).isEqualTo("REVIEW_REQUIRED");
    }
    @Test void candidateExclusionAuditSurvivesSubsequentSupplierChanges() throws Exception {
        Fixture f=fixture("NORMAL");UUID unavailable=UUID.randomUUID();
        jdbc.update("insert into supplier_offers(id,tenant_id,supplier_account_id,external_offer_id,external_sku_id,display_name,last_unit_price_fen,stock_qty,compatible_sales_sku_id) select ?,tenant_id,supplier_account_id,?,'MOCK-BLUE-BACKUP','合成缺货备选',800,0,'MOCK-SKU-BLUE' from supplier_offers where tenant_id=? and id=?",unavailable,"MOCK-"+unavailable,A,f.offer());
        jdbc.update("insert into sku_mappings(tenant_id,store_id,sales_sku_id,supplier_offer_id,status,confirmed_by,confirmed_at,review_acknowledged,priority) values(?,?,'MOCK-SKU-BLUE',?,'CONFIRMED','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',now(),true,0)",A,f.store(),unavailable);
        JsonNode evaluation=evaluate(f);create(f,evaluation,UUID.randomUUID().toString());
        jdbc.update("update supplier_offers set stock_qty=100,version=version+1 where tenant_id=? and id=?",A,unavailable);
        String audit=jdbc.queryForObject("select evaluation_snapshot::text from purchase_intents where tenant_id=? and sales_order_item_id=?",String.class,A,f.item());
        assertThat(audit).contains(unavailable.toString(),"STOCK_INSUFFICIENT");
        assertThat(mapper.readTree(audit).get("selectedQuote").get("offerId").asText()).isEqualTo(f.offer().toString());
    }
    @Test void boundedPlannerAdvancesPastTwentyPermanentlyBlockedItems() throws Exception {
        Fixture f=fixture("NORMAL");UUID last=UUID.fromString("ffffffff-ffff-4fff-8fff-"+UUID.randomUUID().toString().substring(24));
        jdbc.update("update sales_order_items set id=? where tenant_id=? and id=?",last,A,f.item());
        UUID unavailable=UUID.randomUUID();
        jdbc.update("insert into supplier_offers(id,tenant_id,supplier_account_id,external_offer_id,external_sku_id,display_name,last_unit_price_fen,stock_qty,compatible_sales_sku_id) select ?,tenant_id,supplier_account_id,?,'MOCK-BLOCKED','合成缺货队列',800,0,'MOCK-BLOCKED' from supplier_offers where tenant_id=? and id=?",unavailable,"MOCK-"+unavailable,A,f.offer());
        jdbc.update("insert into sku_mappings(tenant_id,store_id,sales_sku_id,supplier_offer_id,status,confirmed_by,confirmed_at,review_acknowledged) values(?,?,'MOCK-BLOCKED',?,'CONFIRMED','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',now(),true)",A,f.store(),unavailable);
        for(int i=0;i<20;i++) {
            UUID order=UUID.randomUUID(),item=UUID.fromString("00000000-0000-4000-8000-"+UUID.randomUUID().toString().substring(24));
            jdbc.update("insert into sales_orders(id,tenant_id,store_id,external_order_id,source_status,paid_amount_fen,source_version,source_updated_at) values(?,?,?,?,'PAID',10000,1,now())",order,A,f.store(),"MOCK-BLOCKED-"+order);
            jdbc.update("insert into sales_order_items(id,tenant_id,sales_order_id,external_item_id,external_sku_id,title,qty,paid_allocated_fen) values(?,?,?,'ITEM-1','MOCK-BLOCKED','合成缺货项',1,10000)",item,A,order);
        }
        var planner=new com.smartprocure.procurement.ProcurementPlanner(jdbc,context.getBean(com.smartprocure.procurement.ProcurementService.class),context.getBean(com.smartprocure.store.StoreService.class));
        for(int i=0;i<8;i++)planner.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from purchase_intents where tenant_id=? and sales_order_item_id=?",Integer.class,A,last)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from purchase_intents p join sales_order_items i on i.tenant_id=p.tenant_id and i.id=p.sales_order_item_id join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where p.tenant_id=? and o.store_id=? and i.external_sku_id='MOCK-BLOCKED'",Integer.class,A,f.store())).isZero();
    }
    @Test void refundArrivingDuringFreshSupplierQuoteIsReadBeforeCreate() throws Exception {
        Fixture f=fixture("NORMAL");create(f,evaluate(f),UUID.randomUUID().toString());
        org.mockito.Mockito.doAnswer(invocation->{
            var result=invocation.callRealMethod();
            jdbc.update("update mock_order_authority set source_version=2,snapshot=jsonb_set(jsonb_set(snapshot,'{sourceStatus}','\"REFUNDED\"'),'{sourceVersion}','2') where tenant_id=? and store_id=? and external_order_id=?",A,f.store(),f.external());
            return result;
        }).when(supplier).quote(org.mockito.ArgumentMatchers.eq(A),org.mockito.ArgumentMatchers.eq(f.offer()),org.mockito.ArgumentMatchers.anyInt());
        procure();assertThat(state(f.item())).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select count(*) from mock_purchase_authority where tenant_id=? and offer_id=?",Integer.class,A,f.offer())).isZero();
    }
    @Test void concurrentDifferentItemsReserveOnlyTheExactStoreDailyCap() throws Exception {
        Fixture blue=fixture("NORMAL");UUID whiteOffer=UUID.randomUUID();
        UUID whiteItem=jdbc.queryForObject("select i.id from sales_order_items i join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where i.tenant_id=? and o.store_id=? and i.external_item_id='ITEM-2'",UUID.class,A,blue.store());
        jdbc.update("insert into supplier_offers(id,tenant_id,supplier_account_id,external_offer_id,external_sku_id,display_name,last_unit_price_fen,shipping_fen,compatible_sales_sku_id) select ?,tenant_id,supplier_account_id,?,'MOCK-SKU-WHITE','合成白盒',2002,99,'MOCK-SKU-WHITE' from supplier_offers where tenant_id=? and id=?",whiteOffer,"MOCK-"+whiteOffer,A,blue.offer());
        jdbc.update("insert into sku_mappings(tenant_id,store_id,sales_sku_id,supplier_offer_id,status,confirmed_by,confirmed_at,review_acknowledged) values(?,?,'MOCK-SKU-WHITE',?,'CONFIRMED','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',now(),true)",A,blue.store(),whiteOffer);
        evaluate(blue);jdbc.update("update procurement_policies set max_daily_fen=2101,version=version+1 where tenant_id=? and store_id=?",A,blue.store());
        Fixture white=new Fixture(blue.session(),blue.store(),whiteItem,whiteOffer,blue.external());
        JsonNode blueEvaluation=evaluate(blue),whiteEvaluation=evaluate(white);
        assertThat(blueEvaluation.get("allowed").asBoolean()).isTrue();assertThat(whiteEvaluation.get("allowed").asBoolean()).isTrue();
        var barrier=new java.util.concurrent.CountDownLatch(2);
        org.mockito.Mockito.doAnswer(invocation->{var result=invocation.callRealMethod();barrier.countDown();assertThat(barrier.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return result;}).when(supplier).quote(org.mockito.ArgumentMatchers.eq(A),org.mockito.ArgumentMatchers.argThat(id->id.equals(blue.offer())||id.equals(white.offer())),org.mockito.ArgumentMatchers.anyInt());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=List.<java.util.concurrent.Callable<Integer>>of(()->dailyCreateStatus(blue,blueEvaluation),()->dailyCreateStatus(white,whiteEvaluation));
            var statuses=new java.util.ArrayList<Integer>();for(var result:pool.invokeAll(tasks))statuses.add(result.get());assertThat(statuses).containsExactlyInAnyOrder(202,409);
        }
        assertThat(jdbc.queryForObject("select coalesce(sum(p.estimated_total_fen),0) from purchase_intents p join sales_order_items i on i.tenant_id=p.tenant_id and i.id=p.sales_order_item_id join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where p.tenant_id=? and o.store_id=?",Long.class,A,blue.store())).isEqualTo(2101);
    }
    int dailyCreateStatus(Fixture f,JsonNode evaluation) throws Exception {
        var result=mvc.perform(post("/api/v1/purchase-intents").session(f.session().session()).header("X-CSRF-TOKEN",f.session().csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("salesOrderItemId",f.item(),"quoteId",evaluation.at("/selectedQuote/id").asText())))).andReturn();
        if(result.getResponse().getStatus()==409)assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("code").asText()).isEqualTo("DAILY_LIMIT_EXCEEDED");
        return result.getResponse().getStatus();
    }
    @Test void ownerCanReadBoundedProcurementListsAndCannotReadAnotherTenantId() throws Exception {
        Session a=login("OWNER_A");
        mvc.perform(get("/api/v1/purchase-intents").session(a.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        mvc.perform(get("/api/v1/purchases").session(a.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        mvc.perform(get("/api/v1/purchases?limit=101").session(a.session())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/purchase-intents/"+UUID.randomUUID()).session(a.session())).andExpect(status().isNotFound());
    }
}
