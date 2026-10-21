package com.smartprocure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.sales.SalesEventWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.*;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.schemas=m3_payment_test","spring.flyway.default-schema=m3_payment_test","spring.datasource.hikari.schema=m3_payment_test","app.mock.worker-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="M0_TEST_DB_URL",matches=".+")
class M3PaymentIT {
    static final UUID A=UUID.fromString("11111111-1111-4111-8111-111111111111");
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @org.springframework.test.context.bean.override.mockito.MockitoSpyBean JdbcTemplate jdbc;
    @Autowired ApplicationContext context; @Autowired SalesEventWorker salesWorker;
    @org.springframework.boot.test.mock.mockito.SpyBean com.smartprocure.provider.MockPaymentAdapter payment;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("M0_TEST_DB_URL")+(System.getenv("M0_TEST_DB_URL").contains("?")?"&":"?")+"currentSchema=m3_payment_test");
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("M0_TEST_DB_USER","postgres"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("M0_TEST_DB_PASSWORD",""));
    }
    static class ControlledClock extends java.time.Clock {
        final java.util.concurrent.atomic.AtomicReference<java.time.Instant> current=new java.util.concurrent.atomic.AtomicReference<>();
        @Override public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        @Override public java.time.Clock withZone(java.time.ZoneId zone){return this;}
        @Override public java.time.Instant instant(){var value=current.get();return value==null?java.time.Instant.now():value;}
    }
    @org.springframework.boot.test.context.TestConfiguration static class ClockConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary ControlledClock controlledClock(){return new ControlledClock();}
    }
    @Autowired ControlledClock clock;
    @BeforeEach void cleanSyntheticBusinessFixtures() {
        clock.current.set(null);
        jdbc.execute("truncate stores,supplier_accounts cascade");
        jdbc.update("delete from audit_logs");
        jdbc.update("update payment_policies set mode='MANUAL',is_paused=true,max_single_fen=50000,max_daily_fen=200000,max_store_single_fen=50000,max_store_daily_fen=200000,min_estimated_profit_fen=0,new_supplier_first_order_manual=true");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/demo/m1-seed.sql"),new org.springframework.core.io.ClassPathResource("db/demo/m2-seed.sql"),new org.springframework.core.io.ClassPathResource("db/demo/m3-seed.sql")).execute(jdbc.getDataSource());
    }
    record Session(MockHttpSession session,String csrf) { }
    record Fixture(Session s,UUID store,UUID item,UUID offer,UUID account,UUID purchase,String external) { }
    JsonNode data(org.springframework.test.web.servlet.MvcResult r)throws Exception{return mapper.readTree(r.getResponse().getContentAsString()).get("data");}
    Session login(String persona)throws Exception {
        var r=mvc.perform(get("/api/v1/auth/csrf")).andReturn();var s=(MockHttpSession)r.getRequest().getSession();
        String token=data(r).get("token").asText();
        mvc.perform(post("/api/v1/auth/login").session(s).header("X-CSRF-TOKEN",token).contentType("application/json").content(mapper.writeValueAsString(Map.of("persona",persona)))).andExpect(status().isOk());
        return new Session(s,data(mvc.perform(get("/api/v1/auth/csrf").session(s)).andReturn()).get("token").asText());
    }
    JsonNode policy(Session s)throws Exception{return data(mvc.perform(get("/api/v1/payment-policy").session(s.session())).andExpect(status().isOk()).andReturn());}
    void policy(Session s,String mode,boolean paused,long daily,long storeDaily)throws Exception {
        JsonNode old=policy(s);
        Map<String,Object> p=new LinkedHashMap<>();p.put("mode",mode);p.put("maxSingleFen",50000);p.put("maxDailyFen",daily);p.put("maxStoreSingleFen",50000);p.put("maxStoreDailyFen",storeDaily);p.put("minEstimatedProfitFen",0);p.put("isPaused",paused);p.put("newSupplierFirstOrderManual",true);p.put("version",old.get("version").asLong());
        mvc.perform(put("/api/v1/payment-policy").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(p))).andExpect(status().isOk());
    }
    Fixture fixture(String mode,String behavior,boolean mandate)throws Exception {
        Session s=login("OWNER_A");policy(s,mode,false,200000,200000);
        UUID store=UUID.fromString(data(mvc.perform(post("/api/v1/stores").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("platform","DOUYIN","externalShopId","MOCK-"+UUID.randomUUID(),"displayName","M3合成店")))).andExpect(status().isOk()).andReturn()).get("id").asText());
        String external="MOCK-"+UUID.randomUUID();
        mvc.perform(post("/api/v1/mock/stores/"+store+"/scenarios").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("externalOrderId",external,"scenario","PAID")))).andExpect(status().isAccepted());salesWorker.runOnce();
        UUID item=jdbc.queryForObject("select i.id from sales_order_items i join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where i.tenant_id=? and o.external_order_id=? and i.external_item_id='ITEM-1'",UUID.class,A,external);
        UUID account=UUID.randomUUID(),offer=UUID.randomUUID();
        jdbc.update("insert into supplier_accounts(id,tenant_id,provider,external_account_id,status) values(?,?,'MOCK_SUPPLIER',?,'ACTIVE')",account,A,"MOCK-"+account);
        jdbc.update("insert into supplier_offers(id,tenant_id,supplier_account_id,external_offer_id,external_sku_id,display_name,last_unit_price_fen,shipping_fen,compatible_sales_sku_id) values(?,?,?,?,?,'合成蓝盒',1001,99,'MOCK-SKU-BLUE')",offer,A,account,"MOCK-"+offer,"MOCK-SKU-BLUE");
        jdbc.update("insert into sku_mappings(tenant_id,store_id,sales_sku_id,supplier_offer_id,status,confirmed_by,confirmed_at,review_acknowledged) values(?,?,'MOCK-SKU-BLUE',?,'CONFIRMED','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',now(),true)",A,store,offer);
        JsonNode evaluation=data(mvc.perform(post("/api/v1/purchase-intents/evaluate").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("salesOrderItemId",item)))).andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/v1/purchase-intents").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("salesOrderItemId",item,"quoteId",evaluation.at("/selectedQuote/id").asText())))).andExpect(status().isAccepted());
        context.getBean("procurementWorker").getClass().getMethod("runOnce").invoke(context.getBean("procurementWorker"));
        UUID purchase=jdbc.queryForObject("select p.id from purchase_orders p join purchase_intents i on i.tenant_id=p.tenant_id and i.id=p.purchase_intent_id where i.tenant_id=? and i.sales_order_item_id=?",UUID.class,A,item);
        if(mandate)jdbc.update("insert into payment_mandates(tenant_id,supplier_account_id,provider,status,max_single_fen,expires_at,last_verified_at) values(?,?,'MOCK_PAYMENT','ACTIVE',50000,now()+interval '30 days',now())",A,account);
        mvc.perform(patch("/api/v1/mock/purchases/"+purchase+"/payment-config").session(s.session()).header("X-CSRF-TOKEN",s.csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("behavior",behavior,"version",0)))).andExpect(status().isOk());
        return new Fixture(s,store,item,offer,account,purchase,external);
    }
    JsonNode command(Fixture f,String suffix,String key,int status)throws Exception {
        return data(mvc.perform(post("/api/v1/purchases/"+f.purchase()+"/"+suffix).session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).header("Idempotency-Key",key)).andExpect(status().is(status)).andReturn());
    }
    JsonNode view(Fixture f)throws Exception{return data(mvc.perform(get("/api/v1/purchases/"+f.purchase()+"/payment-status").session(f.s().session())).andExpect(status().isOk()).andReturn());}
    void worker()throws Exception{context.getBean("paymentWorker").getClass().getMethod("runOnce").invoke(context.getBean("paymentWorker"));}
    void due(Fixture f){jdbc.update("update outbox_events e set available_at=now()-interval '1 second' from payment_attempts a where e.tenant_id=a.tenant_id and e.aggregate_id=a.id and a.tenant_id=? and a.purchase_order_id=? and e.state='RETRY_WAIT'",A,f.purchase());}
    long calls(Fixture f){return jdbc.queryForObject("select coalesce(sum(pay_calls),0) from mock_payment_authority where tenant_id=? and purchase_order_id=?",Long.class,A,f.purchase());}
    void receipt(Fixture f,UUID attempt,String outcome)throws Exception{mvc.perform(post("/api/v1/mock/payment-attempts/"+attempt+"/receipt").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(Map.of("outcome",outcome)))).andExpect(status().isAccepted());}
    @Test void defaultPolicyAndSeedMandatesAreSafe()throws Exception {
        Session s=login("OWNER_B");assertThat(policy(s).get("mode").asText()).isEqualTo("MANUAL");assertThat(policy(s).get("isPaused").asBoolean()).isTrue();
        mvc.perform(get("/api/v1/payment-mandates").session(s.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].status").value("PENDING"));
    }
    @Test void t005AutoWithoutMandateWaitsWithNoAttemptOrCall()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",false);JsonNode v=command(f,"payments",UUID.randomUUID().toString(),202);worker();
        assertThat(v.get("state").asText()).isEqualTo("WAIT_MERCHANT_PAYMENT");assertThat(view(f).get("attempt").isNull()).isTrue();assertThat(calls(f)).isZero();
    }
    @Test void t006AutoPaysOnlyAfterDurableAttemptAndAuthorityVerification()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);worker();JsonNode v=view(f);
        assertThat(v.get("state").asText()).isEqualTo("PAID");assertThat(v.get("paidFen").asLong()).isEqualTo(2101);assertThat(v.at("/attempt/providerPaymentReference").asText()).startsWith("MOCK-PAYMENT-");assertThat(calls(f)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from purchase_orders where tenant_id=? and id=?",String.class,A,f.purchase())).isEqualTo("PAID");worker();assertThat(calls(f)).isEqualTo(1);
    }
    @Test void t007TimeoutAfterPaymentFreezesThenQueriesWithoutSecondPay()throws Exception {
        Fixture f=fixture("AUTO","PAY_TIMEOUT_AFTER",true);String key=UUID.randomUUID().toString();command(f,"payments",key,202);worker();assertThat(view(f).get("state").asText()).isEqualTo("UNKNOWN");mvc.perform(post("/api/v1/purchases/"+f.purchase()+"/payments").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).header("Idempotency-Key",key)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_STATUS_UNKNOWN"));due(f);worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");assertThat(calls(f)).isEqualTo(1);
    }
    @Test void t008UnknownRejectsEvenOldCheckoutKeyAndPaymentsReplay()throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false);String checkout=UUID.randomUUID().toString();JsonNode entry=command(f,"checkout-entry",checkout,200);UUID attempt=UUID.fromString(entry.get("attemptId").asText());
        jdbc.update("update payment_attempts set state='UNKNOWN',started_at=now() where tenant_id=? and id=?",A,attempt);
        for(String path:List.of("checkout-entry","payments"))mvc.perform(post("/api/v1/purchases/"+f.purchase()+"/"+path).session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).header("Idempotency-Key",checkout)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_STATUS_UNKNOWN"));
        assertThat(calls(f)).isZero();
    }
    @Test void t014SupportIsReadOnlyAndAssignedStoreScopedAndCsrfApplies()throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false);Session support=login("SUPPORT_A");
        mvc.perform(put("/api/v1/payment-policy").session(support.session()).header("X-CSRF-TOKEN",support.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/purchases/"+f.purchase()+"/payment-status").session(support.session())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/purchases/"+f.purchase()+"/checkout-entry").session(support.session()).header("X-CSRF-TOKEN",support.csrf()).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/purchases/"+f.purchase()+"/payments").session(f.s().session()).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isForbidden());
    }
    @Test void t017GlobalStopBlocksNewButAllowsQueryOfInFlight()throws Exception {
        Fixture f=fixture("AUTO","PAY_TIMEOUT_AFTER",true);command(f,"payments",UUID.randomUUID().toString(),202);worker();
        jdbc.update("insert into feature_flags(flag_name,enabled) values('payment_emergency_stop',true) on conflict(flag_name) where tenant_id is null do update set enabled=true");
        try{command(f,"payment-reconcile",UUID.randomUUID().toString(),202);worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");assertThat(policy(f.s()).get("globalPaused").asBoolean()).isTrue();}finally{jdbc.update("delete from feature_flags where tenant_id is null and flag_name='payment_emergency_stop'");}
    }
    @Test void t022UnpaidReceiptCannotMarkManualPaid()throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false);UUID id=UUID.fromString(command(f,"checkout-entry",UUID.randomUUID().toString(),200).get("attemptId").asText());receipt(f,id,"UNPAID");worker();assertThat(view(f).get("state").asText()).isEqualTo("WAIT_MERCHANT_PAYMENT");assertThat(calls(f)).isZero();
        receipt(f,id,"PAID");assertThat(view(f).get("state").asText()).isNotEqualTo("PAID");worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");receipt(f,id,"UNPAID");worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");
    }
    @Test void t023RevokedMandateBetweenQueueAndPrepareBlocksNewCall()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);jdbc.update("update payment_mandates set status='REVOKED',version=version+1 where tenant_id=? and supplier_account_id=?",A,f.account());worker();assertThat(calls(f)).isZero();assertThat(view(f).get("state").asText()).isEqualTo("WAIT_MERCHANT_PAYMENT");
    }
    @Test void t024ConcurrentWorkersAndExpiredLeaseNeverPayTwice()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);
        try(var pool=Executors.newFixedThreadPool(3)){for(var r:pool.invokeAll(List.<java.util.concurrent.Callable<Void>>of(()->{worker();return null;},()->{worker();return null;},()->{worker();return null;})))r.get();}
        assertThat(view(f).get("state").asText()).isEqualTo("PAID");assertThat(calls(f)).isEqualTo(1);
        jdbc.update("update outbox_events e set state='PROCESSING',lease_until=now()-interval '1 second',lease_token=? from payment_attempts a where e.tenant_id=a.tenant_id and e.aggregate_id=a.id and a.tenant_id=? and a.purchase_order_id=?",UUID.randomUUID(),A,f.purchase());worker();assertThat(calls(f)).isEqualTo(1);
    }
    @Test void timeoutBeforePaymentRemainsFrozenThroughFiveQueries()throws Exception {
        Fixture f=fixture("AUTO","PAY_TIMEOUT_BEFORE",true);command(f,"payments",UUID.randomUUID().toString(),202);worker();for(int i=0;i<5;i++){due(f);worker();}assertThat(view(f).get("state").asText()).isEqualTo("MANUAL_RECONCILIATION");assertThat(calls(f)).isEqualTo(1);
    }
    @Test void ruleBasedFirstSupplierRequiresManualAndCrossTenantKnownIdsAreHidden()throws Exception {
        Fixture f=fixture("RULE_BASED","NORMAL",true);assertThat(command(f,"payments",UUID.randomUUID().toString(),202).get("decisionCode").asText()).isEqualTo("NEW_SUPPLIER_REVIEW");worker();assertThat(calls(f)).isZero();Session b=login("OWNER_B");
        for(String path:List.of("payment-status","payment-reconcile","payments","checkout-entry")){var r=path.equals("payment-status")?get("/api/v1/purchases/"+f.purchase()+"/"+path):post("/api/v1/purchases/"+f.purchase()+"/"+path);mvc.perform(r.session(b.session()).header("X-CSRF-TOKEN",b.csrf()).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isNotFound());}
    }
    @Test void nonPayablePurchaseIsBlockedInViewAndWaitFilter()throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false);jdbc.update("update purchase_orders set state='CREATE_UNKNOWN' where tenant_id=? and id=?",A,f.purchase());
        assertThat(view(f).get("state").asText()).isEqualTo("BLOCKED");assertThat(view(f).get("decisionCode").asText()).isEqualTo("PURCHASE_NOT_PAYABLE");
        JsonNode q=data(mvc.perform(get("/api/v1/payment-queue?state=WAIT_MERCHANT_PAYMENT&storeId="+f.store()).session(f.s().session())).andExpect(status().isOk()).andReturn());assertThat(q.get("total").asLong()).isZero();
    }
    @Test void cancelledSalesAuthorityAfterCheckoutCannotStartManualPayment()throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false);UUID id=UUID.fromString(command(f,"checkout-entry",UUID.randomUUID().toString(),200).get("attemptId").asText());
        jdbc.update("update sales_orders set source_status='REFUNDED',version=version+1 where tenant_id=? and external_order_id=?",A,f.external());
        mvc.perform(post("/api/v1/mock/payment-attempts/"+id+"/receipt").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"outcome\":\"PAID\"}")).andExpect(status().isConflict());assertThat(calls(f)).isZero();assertThat(view(f).get("state").asText()).isNotEqualTo("PAID");
    }
    @Test void currentAuthorityMismatchFreezesAndNeverMarksPaid()throws Exception {
        Fixture f=fixture("AUTO","AUTHORITY_MISMATCH",true);command(f,"payments",UUID.randomUUID().toString(),202);worker();assertThat(view(f).get("state").asText()).isEqualTo("UNKNOWN");assertThat(view(f).get("paidFen").asLong()).isZero();assertThat(calls(f)).isEqualTo(1);
        mvc.perform(get("/api/v1/payment-reconciliations?storeId="+f.store()).session(f.s().session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].outcome").value("MISMATCH"));
    }
    @Test void concurrentTenantAndStoreBudgetsReserveOnlyOnePurchase()throws Exception {
        for(boolean sameStore:List.of(false,true)) {
            Fixture f=fixture("AUTO","NORMAL",true),g=fixture("AUTO","NORMAL",true);
            if(sameStore){jdbc.update("update sales_orders set store_id=? where tenant_id=? and external_order_id=?",f.store(),A,g.external());jdbc.update("update sku_mappings set store_id=? where tenant_id=? and supplier_offer_id=?",f.store(),A,g.offer());}
            policy(f.s(),"AUTO",false,sameStore?200000:2101,2101);
            var barrier=new java.util.concurrent.CountDownLatch(2);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var jobs=List.<java.util.concurrent.Callable<JsonNode>>of(()->{barrier.countDown();barrier.await();return command(f,"payments",UUID.randomUUID().toString(),202);},()->{barrier.countDown();barrier.await();return command(g,"payments",UUID.randomUUID().toString(),202);});
                var values=new ArrayList<JsonNode>();for(var r:pool.invokeAll(jobs))values.add(r.get());
                assertThat(values.stream().filter(v->!v.get("attempt").isNull()).count()).isEqualTo(1);
                assertThat(values.stream().filter(v->v.get("attempt").isNull()).findFirst().orElseThrow().get("decisionCode").asText()).isEqualTo(sameStore?"STORE_DAILY_LIMIT_EXCEEDED":"TENANT_DAILY_LIMIT_EXCEEDED");
            }
            assertThat(jdbc.queryForObject("select sum(reserved_fen) from payment_budget_reservations where tenant_id=? and state='RESERVED' and budget_date=(now() at time zone 'UTC')::date",Long.class,A)).isEqualTo(2101);
            cleanSyntheticBusinessFixtures();
        }
    }
    @Test void utcDailyBudgetRollsOverIndependentOfSessionTimezone()throws Exception {
        Fixture old=fixture("MANUAL","NORMAL",false);command(old,"checkout-entry",UUID.randomUUID().toString(),200);jdbc.update("update payment_budget_reservations set budget_date=(now() at time zone 'UTC')::date-1 where tenant_id=?",A);
        Fixture fresh=fixture("MANUAL","NORMAL",false);policy(fresh.s(),"MANUAL",false,2101,2101);
        var principal=context.getBean(com.smartprocure.tenant.TenantIdentityRepository.class).findActive(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"),A).orElseThrow();
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(st->{
            int hour=java.time.Instant.now().atOffset(java.time.ZoneOffset.UTC).getHour();jdbc.execute("set local time zone '"+(hour>=10?"Pacific/Kiritimati":"Pacific/Pago_Pago")+"'");
            assertThat(jdbc.queryForObject("select current_date<>(now() at time zone 'UTC')::date",Boolean.class)).isTrue();
            context.getBean(com.smartprocure.payment.PaymentService.class).checkoutEntry(principal,fresh.purchase(),UUID.randomUUID().toString());
            assertThat(context.getBean(com.smartprocure.payment.PaymentPolicyService.class).used(A,null)).isEqualTo(2101);
            assertThat(jdbc.queryForObject("select bool_and(b.budget_date=(now() at time zone 'UTC')::date) from payment_budget_reservations b join payment_attempts a on a.tenant_id=b.tenant_id and a.id=b.payment_attempt_id where a.tenant_id=? and a.purchase_order_id=?",Boolean.class,A,fresh.purchase())).isTrue();
        });
    }
    @Test void completionCasFailureRollsBackThenRecoversByQueryOnly()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);
        jdbc.execute("create function m3_payment_test.reject_payment_done() returns trigger language plpgsql as $$ begin if new.event_type='M3_PAYMENT' and new.state='DONE' then return null; end if; return new; end $$");jdbc.execute("create trigger reject_payment_done before update on outbox_events for each row execute function m3_payment_test.reject_payment_done()");
        try{worker();assertThat(view(f).get("state").asText()).isEqualTo("UNKNOWN");assertThat(view(f).get("paidFen").asLong()).isZero();assertThat(calls(f)).isEqualTo(1);}finally{jdbc.execute("drop trigger reject_payment_done on outbox_events");jdbc.execute("drop function m3_payment_test.reject_payment_done()");}
        due(f);worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");assertThat(calls(f)).isEqualTo(1);
    }
    @Test void auditFailureAfterEffectRollsBackPaidAndRetainsBudgetForQuery()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);
        jdbc.execute("create function m3_payment_test.reject_payment_audit() returns trigger language plpgsql as $$ begin if new.action='MOCK_PAYMENT_PAID' then raise exception 'synthetic audit failure'; end if; return new; end $$");jdbc.execute("create trigger reject_payment_audit before insert on audit_logs for each row execute function m3_payment_test.reject_payment_audit()");
        try{worker();assertThat(view(f).get("state").asText()).isEqualTo("UNKNOWN");assertThat(view(f).get("purchaseState").asText()).isEqualTo("AWAITING_PAYMENT");assertThat(jdbc.queryForObject("select b.state from payment_budget_reservations b join payment_attempts a on a.tenant_id=b.tenant_id and a.id=b.payment_attempt_id where a.tenant_id=? and a.purchase_order_id=?",String.class,A,f.purchase())).isEqualTo("RESERVED");}finally{jdbc.execute("drop trigger reject_payment_audit on audit_logs");jdbc.execute("drop function m3_payment_test.reject_payment_audit()");}
        due(f);worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");assertThat(calls(f)).isEqualTo(1);
    }
    @Test void payingLeaseRecoveryQueriesWithoutLaunching()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);JsonNode v=command(f,"payments",UUID.randomUUID().toString(),202);UUID id=UUID.fromString(v.at("/attempt/id").asText());
        jdbc.update("update payment_attempts set state='PAYING',started_at=now() where tenant_id=? and id=?",A,id);jdbc.update("update outbox_events set state='PROCESSING',lease_token=?,lease_until=now()-interval '1 second' where tenant_id=? and aggregate_id=?",UUID.randomUUID(),A,id);
        worker();assertThat(view(f).get("state").asText()).isEqualTo("UNKNOWN");assertThat(calls(f)).isZero();
    }
    @Test void providerCallsRunOutsideLocalTransactionsAndLaunchIsDurable()throws Exception {
        org.mockito.Mockito.doAnswer(inv->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var r=(com.smartprocure.provider.PaymentProviderAdapter.Request)inv.getArgument(1);assertThat(jdbc.queryForObject("select state from payment_attempts where tenant_id=? and purchase_order_id=?",String.class,A,r.purchaseId())).isEqualTo("PAYING");assertThat(jdbc.queryForObject("select count(*) from audit_logs where tenant_id=? and action='MOCK_PAYMENT_LAUNCH_COMMITTED'",Long.class,A)).isEqualTo(1);return inv.callRealMethod();}).when(payment).pay(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(inv->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return inv.callRealMethod();}).when(payment).query(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");
    }
    @Test void explicitQueryResolvesAfterBoundedDeadLetterWithoutNewPay()throws Exception {
        Fixture f=fixture("AUTO","QUERY_TIMEOUT",true);command(f,"payments",UUID.randomUUID().toString(),202);worker();for(int i=0;i<5;i++){due(f);worker();}assertThat(view(f).get("state").asText()).isEqualTo("MANUAL_RECONCILIATION");
        mvc.perform(patch("/api/v1/mock/purchases/"+f.purchase()+"/payment-config").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).contentType("application/json").content("{\"behavior\":\"NORMAL\",\"version\":1}")).andExpect(status().isOk());command(f,"payment-reconcile",UUID.randomUUID().toString(),202);worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");assertThat(calls(f)).isEqualTo(1);
    }

    @Test void queuedPriorUtcDayBudgetsRebaseAtLaunchWithoutExceedingTodayCap()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true),g=fixture("AUTO","NORMAL",true);policy(f.s(),"AUTO",false,2101,2101);
        command(f,"payments",UUID.randomUUID().toString(),202);jdbc.update("update payment_budget_reservations set budget_date=(now() at time zone 'UTC')::date-1 where tenant_id=?",A);command(g,"payments",UUID.randomUUID().toString(),202);jdbc.update("update payment_budget_reservations set budget_date=(now() at time zone 'UTC')::date-1 where tenant_id=?",A);
        try(var pool=Executors.newFixedThreadPool(2)){for(var r:pool.invokeAll(List.<java.util.concurrent.Callable<Void>>of(()->{worker();return null;},()->{worker();return null;})))r.get();}
        assertThat(calls(f)+calls(g)).isEqualTo(1);assertThat(jdbc.queryForObject("select count(*) from payment_attempts where tenant_id=? and state='PAID'",Long.class,A)).isEqualTo(1);
        assertThat(context.getBean(com.smartprocure.payment.PaymentPolicyService.class).used(A,null)).isEqualTo(2101);
    }

    @Test void manualReceiptChecksAuthorityEvenBeforeLocalCancellationEventRuns()throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false);UUID id=UUID.fromString(command(f,"checkout-entry",UUID.randomUUID().toString(),200).get("attemptId").asText());
        jdbc.update("update mock_order_authority set source_version=2,snapshot=jsonb_set(jsonb_set(snapshot,'{sourceStatus}','\"REFUNDED\"'),'{sourceVersion}','2') where tenant_id=? and store_id=? and external_order_id=?",A,f.store(),f.external());
        mvc.perform(post("/api/v1/mock/payment-attempts/"+id+"/receipt").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"outcome\":\"PAID\"}")).andExpect(status().isConflict());assertThat(view(f).get("paidFen").asLong()).isZero();
    }
    @Test void explicitFailedPaymentIsTerminalAndReleasesBudgetWithoutAutomaticRetry()throws Exception {
        Fixture f=fixture("AUTO","DECLINED",true);command(f,"payments",UUID.randomUUID().toString(),202);worker();assertThat(view(f).get("state").asText()).isEqualTo("FAILED");assertThat(context.getBean(com.smartprocure.payment.PaymentPolicyService.class).used(A,null)).isZero();
        command(f,"payments",UUID.randomUUID().toString(),202);worker();assertThat(calls(f)).isEqualTo(1);mvc.perform(post("/api/v1/purchases/"+f.purchase()+"/checkout-entry").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isConflict());
    }
    @Test void wrongAccountAmountOrCurrencyCannotPassAuthorityCheck()throws Exception {
        for(String field:List.of("account","amount","currency")){
            Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);
            org.mockito.Mockito.doAnswer(inv->{var r=(com.smartprocure.provider.PaymentProviderAdapter.Snapshot)inv.callRealMethod();return new com.smartprocure.provider.PaymentProviderAdapter.Snapshot(r.purchaseId(),field.equals("account")?UUID.randomUUID():r.supplierAccountId(),r.externalOrderId(),field.equals("amount")?r.amountFen()+1:r.amountFen(),field.equals("currency")?"USD":r.currency(),r.state(),r.paymentReference());}).when(payment).query(org.mockito.ArgumentMatchers.eq(A),org.mockito.ArgumentMatchers.argThat(r->r.purchaseId().equals(f.purchase())));
            worker();assertThat(view(f).get("state").asText()).isEqualTo("UNKNOWN");assertThat(view(f).get("paidFen").asLong()).isZero();assertThat(calls(f)).isEqualTo(1);org.mockito.Mockito.reset(payment);cleanSyntheticBusinessFixtures();
        }
    }
    @Test void ruleBasedCanRunAfterVerifiedFirstSupplierPayment()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);worker();
        // A second synthetic item shares the verified account and its confirmed offer.
        UUID oldOffer=f.offer();Fixture g=fixture("RULE_BASED","NORMAL",true);
        jdbc.update("update supplier_offers set supplier_account_id=? where tenant_id=? and id=?",f.account(),A,g.offer());jdbc.update("update purchase_intents set supplier_account_id=? where tenant_id=? and sales_order_item_id=?",f.account(),A,g.item());jdbc.update("update purchase_orders set supplier_account_id=? where tenant_id=? and id=?",f.account(),A,g.purchase());
        jdbc.update("update mock_purchase_authority set supplier_account_id=? where tenant_id=? and offer_id=?",f.account(),A,g.offer());
        command(g,"payments",UUID.randomUUID().toString(),202);worker();assertThat(view(g).get("state").asText()).isEqualTo("PAID");
    }
    @Test void pausedWorkerAndRevocationStillPermitPendingReadOnlyReconciliation()throws Exception {
        Fixture f=fixture("AUTO","PAY_TIMEOUT_AFTER",true);command(f,"payments",UUID.randomUUID().toString(),202);worker();jdbc.update("update payment_mandates set status='REVOKED' where tenant_id=? and supplier_account_id=?",A,f.account());policy(f.s(),"AUTO",true,200000,200000);
        command(f,"payment-reconcile",UUID.randomUUID().toString(),202);worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");assertThat(calls(f)).isEqualTo(1);
    }
    @Test void globalStopAndTenantPauseBothStopNewManualAndAuto()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);jdbc.update("insert into feature_flags(flag_name,enabled) values('payment_emergency_stop',true) on conflict(flag_name) where tenant_id is null do update set enabled=true");
        try{assertThat(command(f,"payments",UUID.randomUUID().toString(),202).get("decisionCode").asText()).isEqualTo("PAYMENT_PAUSED");worker();assertThat(calls(f)).isZero();mvc.perform(post("/api/v1/purchases/"+f.purchase()+"/checkout-entry").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_PAUSED"));}finally{jdbc.update("delete from feature_flags where tenant_id is null and flag_name='payment_emergency_stop'");}
        policy(f.s(),"MANUAL",true,200000,200000);assertThat(command(f,"payments",UUID.randomUUID().toString(),202).get("decisionCode").asText()).isEqualTo("PAYMENT_PAUSED");assertThat(view(f).get("attempt").isNull()).isTrue();
    }
    @Test void configAndMandateCasInputAndPermissionsAreExplicit()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);UUID mandate=jdbc.queryForObject("select id from payment_mandates where tenant_id=? and supplier_account_id=?",UUID.class,A,f.account());
        mvc.perform(patch("/api/v1/payment-mandates/"+mandate+"/mock-state").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).contentType("application/json").content("{\"status\":\"EXPIRED\",\"maxSingleFen\":2101,\"version\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXPIRED"));
        mvc.perform(patch("/api/v1/payment-mandates/"+mandate+"/mock-state").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).contentType("application/json").content("{\"status\":\"ACTIVE\",\"maxSingleFen\":2101,\"version\":0}")).andExpect(status().isConflict());
        assertThat(command(f,"payments",UUID.randomUUID().toString(),202).get("decisionCode").asText()).isEqualTo("MANDATE_UNAVAILABLE");assertThat(calls(f)).isZero();
        Session b=login("OWNER_B");for(String path:List.of("/api/v1/mock/purchases/"+f.purchase()+"/payment-config","/api/v1/payment-attempts/"+UUID.randomUUID()+"/mock-checkout"))mvc.perform(get(path).session(b.session())).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/payment-mandates/"+mandate+"/mock-state").session(b.session()).header("X-CSRF-TOKEN",b.csrf()).contentType("application/json").content("{\"status\":\"ACTIVE\",\"maxSingleFen\":2101,\"version\":1}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payment-queue?limit=101").session(f.s().session())).andExpect(status().isBadRequest());mvc.perform(get("/api/v1/payment-reconciliations?offset=10001").session(f.s().session())).andExpect(status().isBadRequest());
    }
    @Test void unsafeJsonNumbersAndUnexpectedCredentialsAreRejected()throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false);for(String body:List.of("{\"behavior\":\"NORMAL\",\"version\":0.5}","{\"behavior\":\"NORMAL\",\"version\":-1}","{\"behavior\":\"NORMAL\",\"version\":0,\"password\":\"FORBIDDEN_SYNTHETIC\"}"))mvc.perform(patch("/api/v1/mock/purchases/"+f.purchase()+"/payment-config").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where redacted_details::text like '%FORBIDDEN_SYNTHETIC%'",Long.class)).isZero();
    }
    @Test void financeIncludesApprovedFeeReserveAndProfitGate()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);jdbc.update("update purchase_intents set policy_snapshot=jsonb_set(jsonb_set(policy_snapshot,'{effective,platformFeeFen}','100'),'{effective,reserveFen}','200') where tenant_id=? and sales_order_item_id=?",A,f.item());assertThat(view(f).get("estimatedProfitFen").asLong()).isEqualTo(6199);
        jdbc.update("update payment_policies set min_estimated_profit_fen=6200 where tenant_id=?",A);assertThat(command(f,"payments",UUID.randomUUID().toString(),202).get("decisionCode").asText()).isEqualTo("PROFIT_BELOW_MINIMUM");worker();assertThat(calls(f)).isZero();
    }
    @Test void auditFailureBeforeLaunchProducesNoProviderEffect()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);
        jdbc.execute("create function m3_payment_test.reject_launch_audit() returns trigger language plpgsql as $$ begin if new.action='MOCK_PAYMENT_LAUNCH_COMMITTED' then raise exception 'synthetic audit failure'; end if; return new; end $$");jdbc.execute("create trigger reject_launch_audit before insert on audit_logs for each row execute function m3_payment_test.reject_launch_audit()");
        try{worker();assertThat(calls(f)).isZero();assertThat(view(f).at("/attempt/startedAt").isNull()).isTrue();}finally{jdbc.execute("drop trigger reject_launch_audit on audit_logs");jdbc.execute("drop function m3_payment_test.reject_launch_audit()");}
    }

    @Test void manualReceiptImmediatelyFreezesOldEntryUntilReadOnlyVerification()throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false);String key=UUID.randomUUID().toString();UUID id=UUID.fromString(command(f,"checkout-entry",key,200).get("attemptId").asText());receipt(f,id,"PAID");
        assertThat(view(f).get("state").asText()).isEqualTo("PAYING");mvc.perform(post("/api/v1/purchases/"+f.purchase()+"/checkout-entry").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).header("Idempotency-Key",key)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_STATUS_UNKNOWN"));worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");
    }

    @Test void supportCannotReadTenantWidePolicyBudgetButCanReadAssignedPurchase()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);Session support=login("SUPPORT_A");
        mvc.perform(get("/api/v1/payment-policy").session(support.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payment-mandates").session(support.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        jdbc.update("insert into member_store_access(tenant_id,tenant_member_id,store_id) select tenant_id,id,? from tenant_members where tenant_id=? and user_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2'",f.store(),A);
        mvc.perform(get("/api/v1/purchases/"+f.purchase()+"/payment-status").session(support.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.purchaseId").value(f.purchase().toString()));
        mvc.perform(get("/api/v1/payment-mandates").session(support.session())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }
    @Test void manualPageCrossingUtcDayRebasesBeforeSyntheticPaidEffect()throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false),g=fixture("MANUAL","NORMAL",false);policy(f.s(),"MANUAL",false,2101,2101);
        UUID one=UUID.fromString(command(f,"checkout-entry",UUID.randomUUID().toString(),200).get("attemptId").asText());jdbc.update("update payment_budget_reservations set budget_date=(now() at time zone 'UTC')::date-1 where tenant_id=?",A);
        UUID two=UUID.fromString(command(g,"checkout-entry",UUID.randomUUID().toString(),200).get("attemptId").asText());jdbc.update("update payment_budget_reservations set budget_date=(now() at time zone 'UTC')::date-1 where tenant_id=?",A);receipt(f,one,"PAID");
        mvc.perform(post("/api/v1/mock/payment-attempts/"+two+"/receipt").session(g.s().session()).header("X-CSRF-TOKEN",g.s().csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"outcome\":\"PAID\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TENANT_DAILY_LIMIT_EXCEEDED"));worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");assertThat(view(g).get("state").asText()).isEqualTo("WAIT_MERCHANT_PAYMENT");
    }

    @Test @SuppressWarnings("unchecked") void financialDetailDoesNotMixPurchaseAndAttemptAcrossConcurrentPayment()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);JsonNode before=view(f);
        var read=new java.util.concurrent.CountDownLatch(1);var committed=new java.util.concurrent.CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(inv->{Object rows=inv.callRealMethod();String sql=inv.getArgument(0);if(sql.stripLeading().startsWith("select p.*,i.id intent")&&once.compareAndSet(true,false)){read.countDown();assertThat(committed.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}return rows;}).when(jdbc).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),org.mockito.ArgumentMatchers.any(Object[].class));
        try(var pool=Executors.newSingleThreadExecutor()){
            var future=pool.submit(()->view(f));assertThat(read.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();worker();committed.countDown();JsonNode observed=future.get(5,java.util.concurrent.TimeUnit.SECONDS),after=view(f);assertThat(observed).isIn(before,after);
        }finally{committed.countDown();}
    }

    void runWorkerAcrossBlockedStore(UUID store,java.time.Instant before,java.time.Instant after)throws Exception {
        runAcrossBlockedStore(store,before,after,()->{worker();return null;});
    }
    void runAcrossBlockedStore(UUID store,java.time.Instant before,java.time.Instant after,java.util.concurrent.Callable<Void> action)throws Exception {
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var waiting=new java.util.concurrent.CountDownLatch(1);var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        clock.current.set(before);
        var backendPid=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(inv->{String sql=inv.getArgument(0);if(Thread.currentThread().getName().equals("blocked-payment-launch")&&sql.startsWith("select s.* from stores s")&&sql.contains("for no key update")&&first.compareAndSet(true,false)){
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(clock.instant()).isEqualTo(before);backendPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));waiting.countDown();
        }return inv.callRealMethod();}).when(jdbc).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),org.mockito.ArgumentMatchers.any(Object[].class));
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(st->{
                jdbc.queryForObject("select id from stores where tenant_id=? and id=? for update",UUID.class,A,store);held.countDown();try{assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex){throw new IllegalStateException(ex);}
            }));
            assertThat(held.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var running=pool.submit(()->{Thread.currentThread().setName("blocked-payment-launch");return action.call();});
            try{
                assertThat(waiting.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                long deadline=System.nanoTime()+java.time.Duration.ofSeconds(4).toNanos();String waitType;
                do{waitType=jdbc.queryForObject("select wait_event_type from pg_stat_activity where pid=?",String.class,backendPid.get());if(!"Lock".equals(waitType))Thread.sleep(5);}while(!"Lock".equals(waitType)&&System.nanoTime()<deadline);
                assertThat(waitType).as("the launch transaction must actually wait for the store lock before midnight changes").isEqualTo("Lock");
                clock.current.set(after);
            }finally{release.countDown();}
            holder.get(5,java.util.concurrent.TimeUnit.SECONDS);running.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }finally{release.countDown();}
    }
    @Test void launchUsesOneWallClockDayAfterWaitingForTenantLock()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true),g=fixture("AUTO","NORMAL",true);policy(f.s(),"AUTO",false,2101,2101);
        command(f,"payments",UUID.randomUUID().toString(),202);jdbc.update("update payment_budget_reservations set budget_date='2060-12-31' where tenant_id=?",A);command(g,"payments",UUID.randomUUID().toString(),202);
        jdbc.update("update payment_budget_reservations b set budget_date='2061-01-01' from payment_attempts a where a.tenant_id=b.tenant_id and a.id=b.payment_attempt_id and a.tenant_id=? and a.purchase_order_id=?",A,g.purchase());
        jdbc.update("update payment_mandates set expires_at='2062-01-01' where tenant_id=?",A);
        jdbc.update("update outbox_events e set available_at=now()-interval '1 minute' from payment_attempts a where e.tenant_id=a.tenant_id and e.aggregate_id=a.id and a.tenant_id=? and a.purchase_order_id=?",A,f.purchase());
        java.time.Instant after=java.time.Instant.parse("2061-01-01T00:00:01Z");runWorkerAcrossBlockedStore(f.store(),java.time.Instant.parse("2060-12-31T23:59:59Z"),after);
        assertThat(calls(f)).as("old-day waiting launch must include the already reserved new-day payment").isZero();assertThat(calls(g)).isEqualTo(1);
        assertThat(view(g).at("/attempt/startedAt").asText()).isEqualTo(after.toString());
        assertThat(jdbc.queryForObject("select b.budget_date::text from payment_budget_reservations b join payment_attempts a on a.tenant_id=b.tenant_id and a.id=b.payment_attempt_id where a.tenant_id=? and a.purchase_order_id=?",String.class,A,g.purchase())).isEqualTo("2061-01-01");
    }
    @Test void mandateExpiringWhileWaitingForTenantLockPreventsNewProviderCall()throws Exception {
        Fixture f=fixture("AUTO","NORMAL",true);command(f,"payments",UUID.randomUUID().toString(),202);
        jdbc.update("update payment_mandates set expires_at='2061-01-01T00:00:00Z' where tenant_id=? and supplier_account_id=?",A,f.account());
        runWorkerAcrossBlockedStore(f.store(),java.time.Instant.parse("2060-12-31T23:59:59Z"),java.time.Instant.parse("2061-01-01T00:00:01Z"));
        assertThat(calls(f)).isZero();assertThat(view(f).get("decisionCode").asText()).isEqualTo("MANDATE_UNAVAILABLE");
    }
    @Test void manualLaunchUsesOneWallClockDayAfterWaitingForTenantLock()throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false),g=fixture("MANUAL","NORMAL",false);policy(f.s(),"MANUAL",false,2101,2101);
        UUID first=UUID.fromString(command(f,"checkout-entry",UUID.randomUUID().toString(),200).get("attemptId").asText());
        jdbc.update("update payment_budget_reservations set budget_date='2060-12-31' where tenant_id=?",A);
        UUID second=UUID.fromString(command(g,"checkout-entry",UUID.randomUUID().toString(),200).get("attemptId").asText());
        jdbc.update("update payment_budget_reservations set budget_date='2061-01-01' where tenant_id=? and payment_attempt_id=?",A,second);
        jdbc.update("update payment_attempts set checkout_expires_at='2062-01-01' where tenant_id=?",A);
        java.time.Instant after=java.time.Instant.parse("2061-01-01T00:00:01Z");
        runAcrossBlockedStore(f.store(),java.time.Instant.parse("2060-12-31T23:59:59Z"),after,()->{
            mvc.perform(post("/api/v1/mock/payment-attempts/"+first+"/receipt").session(f.s().session()).header("X-CSRF-TOKEN",f.s().csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"outcome\":\"PAID\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TENANT_DAILY_LIMIT_EXCEEDED"));return null;
        });
        assertThat(view(f).at("/attempt/state").asText()).isEqualTo("NEW");
        receipt(g,second,"PAID");worker();assertThat(view(g).get("state").asText()).isEqualTo("PAID");
        assertThat(view(g).at("/attempt/startedAt").asText()).isEqualTo(after.toString());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MAPPING","OFFER"})
    void manualLaunchHoldsMappingUntilDurablePayingAgainstConcurrentPatch(String target)throws Exception {
        Fixture f=fixture("MANUAL","NORMAL",false);UUID attempt=UUID.fromString(command(f,"checkout-entry",UUID.randomUUID().toString(),200).get("attemptId").asText());UUID mapping=jdbc.queryForObject("select id from sku_mappings where tenant_id=? and supplier_offer_id=?",UUID.class,A,f.offer());Session editor=login("OWNER_A");
        var checked=new java.util.concurrent.CountDownLatch(1);var proceed=new java.util.concurrent.CountDownLatch(1);var updating=new java.util.concurrent.CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(inv->{Object result=inv.callRealMethod();String sql=inv.getArgument(0);if(Thread.currentThread().getName().equals("manual-launch")&&sql.startsWith("select exists(select 1 from sku_mappings")&&once.compareAndSet(true,false)){checked.countDown();assertThat(proceed.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}return result;}).when(jdbc).queryForObject(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.<Class<Object>>any(),org.mockito.ArgumentMatchers.any(Object[].class));
        org.mockito.Mockito.doAnswer(inv->{String sql=inv.getArgument(0);if(Thread.currentThread().getName().equals("mapping-editor")&&(sql.startsWith("update sku_mappings set status=")||sql.startsWith("update supplier_offers set last_unit_price_fen=")))updating.countDown();return inv.callRealMethod();}).when(jdbc).update(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class));
        try(var pool=Executors.newFixedThreadPool(2)){
            var launch=pool.submit(()->{Thread.currentThread().setName("manual-launch");receipt(f,attempt,"PAID");return null;});assertThat(checked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String path="MAPPING".equals(target)?"/api/v1/sku-mappings/"+mapping:"/api/v1/supplier-offers/"+f.offer()+"/mock-config";
            String body="MAPPING".equals(target)?"{\"status\":\"PAUSED\",\"version\":0}":"{\"cipherPlatforms\":[],\"version\":0}";
            var edit=pool.submit(()->{Thread.currentThread().setName("mapping-editor");return mvc.perform(patch(path).session(editor.session()).header("X-CSRF-TOKEN",editor.csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();});
            try{assertThat(updating.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->edit.get(150,java.util.concurrent.TimeUnit.MILLISECONDS)).as("mapping PATCH must wait for manual PAYING commit").isInstanceOf(java.util.concurrent.TimeoutException.class);}finally{proceed.countDown();}
            launch.get(5,java.util.concurrent.TimeUnit.SECONDS);edit.get(5,java.util.concurrent.TimeUnit.SECONDS);worker();assertThat(view(f).get("state").asText()).isEqualTo("PAID");
        }finally{proceed.countDown();}
    }

}
