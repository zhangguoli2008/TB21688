package com.smartprocure.payment;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.M1Support;
import com.smartprocure.provider.PaymentProviderAdapter;
import com.smartprocure.provider.SalesOrderProviderAdapter;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Service
public class PaymentService {
    public enum Behavior { NORMAL,DECLINED,PAY_TIMEOUT_BEFORE,PAY_TIMEOUT_AFTER,QUERY_TIMEOUT,AUTHORITY_MISMATCH }
    public enum Outcome { PAID,UNPAID,FAILED }
    public record ConfigUpdate(@NotNull Behavior behavior,@NotNull @Min(0) @Max(9007199254740991L) Long version) { }
    public record Receipt(@NotNull Outcome outcome) { }
    public record Config(UUID purchaseId,String behavior,long version,String runtimeMode) { }
    public record Attempt(UUID id,UUID purchaseId,int attemptNo,String mode,String state,long amountFen,String currency,UUID mandateId,String providerPaymentReference,String failureCode,Instant startedAt,Instant resolvedAt,Instant lastVerifiedAt,Instant createdAt,long version,long policyVersion) { }
    public record View(UUID purchaseId,UUID storeId,UUID supplierAccountId,String externalOrderId,String purchaseState,long amountFen,String currency,long salesAllocatedFen,long estimatedProfitFen,long paidFen,String state,String decisionCode,Attempt attempt,Instant updatedAt,String runtimeMode) { }
    public record Entry(UUID purchaseId,UUID attemptId,String url,String mode,Instant expiresAt) { }
    public record Checkout(Attempt attempt,UUID purchaseId,long totalFen,String currency,String authorityState,boolean canComplete,Instant expiresAt,String mode) { }
    public record Reconciliation(UUID id,UUID purchaseId,UUID attemptId,String outcome,String localState,Instant createdAt,String runtimeMode) { }
    record Purchase(UUID id,UUID tenant,UUID intent,UUID store,UUID item,UUID order,UUID account,UUID offer,UUID mapping,int qty,int originalQty,int remaining,long allocated,long total,long profit,String currency,String external,String salesExternal,String itemExternal,String sku,String sourceStatus,long sourceVersion,String sourceDigest,String state,String decision,Instant updatedAt) { }
    static final Set<String> FROZEN=Set.of("PAYING","UNKNOWN","MANUAL_RECONCILIATION");
    private static final Set<String> STATES=Set.of("NEW","WAIT_MERCHANT_PAYMENT","PAYING","UNKNOWN","PAID","FAILED","MANUAL_RECONCILIATION","BLOCKED");
    private final JdbcTemplate jdbc;private final M1Support support;private final StoreService stores;private final PaymentPolicyService policies;private final AuditService audit;private final TransactionTemplate tx;private final PaymentProviderAdapter provider;private final SalesOrderProviderAdapter sales;
    public PaymentService(JdbcTemplate jdbc,M1Support support,StoreService stores,PaymentPolicyService policies,AuditService audit,org.springframework.transaction.PlatformTransactionManager manager,PaymentProviderAdapter provider,SalesOrderProviderAdapter sales){this.jdbc=jdbc;this.support=support;this.stores=stores;this.policies=policies;this.audit=audit;this.tx=new TransactionTemplate(manager);this.provider=provider;this.sales=sales;}
    Purchase purchase(UUID tenant,UUID id){return jdbc.query("""
      select p.*,i.id intent,i.sales_order_item_id,i.supplier_offer_id,i.mapping_id,i.qty intent_qty,
        si.sales_order_id,si.qty original_qty,si.qty-si.cancelled_qty-si.fulfilled_qty remaining_qty,si.external_item_id,si.external_sku_id,
        trunc(si.paid_allocated_fen::numeric*i.qty/si.qty)::bigint allocated,
        trunc(si.paid_allocated_fen::numeric*i.qty/si.qty)::bigint-p.total_fen-coalesce((i.policy_snapshot->'effective'->>'platformFeeFen')::bigint,0)-coalesce((i.policy_snapshot->'effective'->>'reserveFen')::bigint,0) profit,
        o.store_id,o.external_order_id sales_external,o.source_status,o.source_version,o.source_digest
      from purchase_orders p join purchase_intents i on i.tenant_id=p.tenant_id and i.id=p.purchase_intent_id
      join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id
      join sales_orders o on o.tenant_id=si.tenant_id and o.id=si.sales_order_id where p.tenant_id=? and p.id=?
      """,(r,n)->new Purchase(id,tenant,r.getObject("intent",UUID.class),r.getObject("store_id",UUID.class),r.getObject("sales_order_item_id",UUID.class),r.getObject("sales_order_id",UUID.class),r.getObject("supplier_account_id",UUID.class),r.getObject("supplier_offer_id",UUID.class),r.getObject("mapping_id",UUID.class),r.getInt("intent_qty"),r.getInt("original_qty"),r.getInt("remaining_qty"),r.getLong("allocated"),r.getLong("total_fen"),r.getLong("profit"),r.getString("currency"),r.getString("external_order_id"),r.getString("sales_external"),r.getString("external_item_id"),r.getString("external_sku_id"),r.getString("source_status"),r.getLong("source_version"),r.getString("source_digest"),r.getString("state"),r.getString("payment_decision_code"),r.getTimestamp("updated_at").toInstant()),tenant,id).stream().findFirst().orElseThrow(M1Support::missing);}
    Purchase scoped(TenantPrincipal p,UUID id){policies.allowed(p.currentTenantId());Purchase purchase=purchase(p.currentTenantId(),id);stores.get(p,purchase.store());return purchase;}
    private Attempt attempt(ResultSet r,int n)throws SQLException{return new Attempt(r.getObject("id",UUID.class),r.getObject("purchase_order_id",UUID.class),r.getInt("attempt_no"),r.getString("mode"),r.getString("state"),r.getLong("amount_fen"),r.getString("currency"),r.getObject("mandate_id",UUID.class),r.getString("provider_payment_reference"),r.getString("failure_code"),PaymentPolicyService.instant(r,"started_at"),PaymentPolicyService.instant(r,"resolved_at"),PaymentPolicyService.instant(r,"last_verified_at"),r.getTimestamp("created_at").toInstant(),r.getLong("version"),r.getLong("policy_version"));}
    Attempt latest(UUID tenant,UUID purchase){return jdbc.query("select * from payment_attempts where tenant_id=? and purchase_order_id=? order by attempt_no desc limit 1",this::attempt,tenant,purchase).stream().findFirst().orElse(null);}
    Attempt attempt(UUID tenant,UUID id){return jdbc.query("select * from payment_attempts where tenant_id=? and id=?",this::attempt,tenant,id).stream().findFirst().orElseThrow(M1Support::missing);}
    private void frozen(Attempt a){if(a!=null&&FROZEN.contains(a.state()))throw M1Support.conflict("PAYMENT_STATUS_UNKNOWN");}
    View view(Purchase p){Attempt a=latest(p.tenant(),p.id());String code=a==null&&!"AWAITING_PAYMENT".equals(p.state())?"PURCHASE_NOT_PAYABLE":p.decision();String state=a==null?"WAIT_MERCHANT_PAYMENT":a.state();if(a!=null&&"NEW".equals(a.state())&&"MANUAL".equals(a.mode()))state="WAIT_MERCHANT_PAYMENT";if(a==null&&code!=null&&!Set.of("MANUAL_PAYMENT_REQUIRED","MANDATE_UNAVAILABLE","NEW_SUPPLIER_REVIEW","SINGLE_LIMIT_EXCEEDED","TENANT_DAILY_LIMIT_EXCEEDED","STORE_DAILY_LIMIT_EXCEEDED","MANDATE_LIMIT_EXCEEDED").contains(code))state="BLOCKED";return new View(p.id(),p.store(),p.account(),p.external(),p.state(),p.total(),p.currency(),p.allocated(),p.profit(),a!=null&&"PAID".equals(a.state())?a.amountFen():0,state,code,a,p.updatedAt(),"MOCK");}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View get(TenantPrincipal p,UUID id){return view(scoped(p,id));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public M1Support.Page<View> queue(TenantPrincipal p,int limit,int offset,UUID store,String state){policies.allowed(p.currentTenantId());M1Support.page(limit,offset);if(state!=null&&!STATES.contains(state))throw M1Support.invalid();if(store!=null)stores.get(p,store);List<Object> args=new ArrayList<>();String where=support.scope(p,"s",args);if(store!=null){where+=" and s.id=?";args.add(store);}String effective="case when a.id is null and po.state<>'AWAITING_PAYMENT' then 'BLOCKED' when a.id is null and po.payment_decision_code is not null and po.payment_decision_code not in ('MANUAL_PAYMENT_REQUIRED','MANDATE_UNAVAILABLE','NEW_SUPPLIER_REVIEW','SINGLE_LIMIT_EXCEEDED','TENANT_DAILY_LIMIT_EXCEEDED','STORE_DAILY_LIMIT_EXCEEDED','MANDATE_LIMIT_EXCEEDED') then 'BLOCKED' when a.id is null or (a.state='NEW' and a.mode='MANUAL') then 'WAIT_MERCHANT_PAYMENT' else a.state end";if(state!=null){where+=" and ("+effective+")=?";args.add(state);}String joins=" from purchase_orders po join purchase_intents i on i.tenant_id=po.tenant_id and i.id=po.purchase_intent_id join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id join sales_orders o on o.tenant_id=si.tenant_id and o.id=si.sales_order_id join stores s on s.tenant_id=o.tenant_id and s.id=o.store_id left join payment_attempts a on a.tenant_id=po.tenant_id and a.purchase_order_id=po.id where "+where;long total=jdbc.queryForObject("select count(*)"+joins,Long.class,args.toArray());args.add(limit);args.add(offset);var ids=jdbc.query("select po.id"+joins+" order by po.created_at desc,po.id desc limit ? offset ?",(r,n)->r.getObject(1,UUID.class),args.toArray());return new M1Support.Page<>(ids.stream().map(id->view(purchase(p.currentTenantId(),id))).toList(),total,limit,offset);}
    String localBlock(Purchase p,PaymentPolicyService.Policy policy,boolean automatic){
        if(policy.globalPaused()||policy.isPaused())return "PAYMENT_PAUSED";
        if(!"AWAITING_PAYMENT".equals(p.state())||p.external()==null)return "PURCHASE_NOT_PAYABLE";
        if(!"PAID".equals(p.sourceStatus())||p.qty()>p.remaining())return "SALES_ORDER_NOT_PAYABLE";
        if(!"CNY".equals(p.currency())||p.total()<0||p.total()>1000000000000L)return "CURRENCY_OR_AMOUNT_INVALID";
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sku_mappings m join supplier_offers f on f.tenant_id=m.tenant_id and f.id=m.supplier_offer_id join supplier_accounts a on a.tenant_id=f.tenant_id and a.id=f.supplier_account_id join stores s on s.tenant_id=m.tenant_id and s.id=m.store_id where m.tenant_id=? and m.id=? and m.store_id=? and m.supplier_offer_id=? and m.sales_sku_id=? and m.status='CONFIRMED' and m.review_acknowledged and f.compatible_sales_sku_id=m.sales_sku_id and f.state='ACTIVE' and a.status='ACTIVE' and jsonb_exists(f.ciphertext_platforms,s.platform) and jsonb_exists(f.regions,'CN'))",Boolean.class,p.tenant(),p.mapping(),p.store(),p.offer(),p.sku())))return "MAPPING_OR_FULFILLMENT_UNAVAILABLE";
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from aftersales_cases where tenant_id=? and sales_order_id=? and state not in ('RESOLVED','REJECTED'))",Boolean.class,p.tenant(),p.order())))return "AFTERSALE_UNRESOLVED";
        if(p.profit()<policy.minEstimatedProfitFen())return "PROFIT_BELOW_MINIMUM";
        if(p.total()>Math.min(100000,Math.min(policy.maxSingleFen(),policy.maxStoreSingleFen())))return "SINGLE_LIMIT_EXCEEDED";
        if(automatic&&"RULE_BASED".equals(policy.mode())&&policy.newSupplierFirstOrderManual()&&!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from payment_attempts a join purchase_orders po on po.tenant_id=a.tenant_id and po.id=a.purchase_order_id where a.tenant_id=? and po.supplier_account_id=? and a.state='PAID' and a.last_verified_at is not null)",Boolean.class,p.tenant(),p.account())))return "NEW_SUPPLIER_REVIEW";
        return null;
    }
    UUID mandate(Purchase p){return jdbc.query("select m.id from payment_mandates m join supplier_accounts a on a.tenant_id=m.tenant_id and a.id=m.supplier_account_id where m.tenant_id=? and m.supplier_account_id=? and m.status='ACTIVE' and m.expires_at>now() and m.last_verified_at is not null and m.max_single_fen>=? and a.status='ACTIVE' and m.provider='MOCK_PAYMENT' and a.provider in ('MOCK','MOCK_SUPPLIER') order by m.id limit 1",(r,n)->r.getObject(1,UUID.class),p.tenant(),p.account(),p.total()).stream().findFirst().orElse(null);}
    void decision(Purchase p,String code){jdbc.update("update purchase_orders set payment_decision_code=?,updated_at=now() where tenant_id=? and id=?",code,p.tenant(),p.id());}
    private Attempt create(Purchase p,PaymentPolicyService.Policy policy,boolean manual,UUID actor){String reason=localBlock(p,policy,!manual);if(reason==null&&!manual&&mandate(p)==null)reason="MANDATE_UNAVAILABLE";if(reason==null&&policies.used(p.tenant(),null)+p.total()>Math.min(1000000,policy.maxDailyFen()))reason="TENANT_DAILY_LIMIT_EXCEEDED";if(reason==null&&policies.used(p.tenant(),p.store())+p.total()>policy.maxStoreDailyFen())reason="STORE_DAILY_LIMIT_EXCEEDED";if(reason!=null){if(reason.equals(p.decision()))return null;decision(p,reason);audit.object(p.tenant(),actor,"MOCK_PAYMENT_BLOCKED","PURCHASE_ORDER",p.id());return null;}
        UUID id=UUID.randomUUID(),mandate=manual?null:mandate(p);String mode=manual?"MANUAL":policy.mode();
        jdbc.update("insert into payment_attempts(id,tenant_id,purchase_order_id,attempt_no,mode,amount_fen,currency,mandate_id,idempotency_key,policy_version) values(?,?,?,1,?,?,?,?,?,?)",id,p.tenant(),p.id(),mode,p.total(),p.currency(),mandate,"MOCK-PAY-"+id,policy.version());
        jdbc.update("insert into payment_budget_reservations(tenant_id,payment_attempt_id,budget_date,reserved_fen) values(?,?,(now() at time zone 'UTC')::date,?)",p.tenant(),id,p.total());decision(p,manual?"MANUAL_PAYMENT_REQUIRED":null);if(!manual)enqueue(p.tenant(),id,false);audit.object(p.tenant(),actor,"MOCK_PAYMENT_PLANNED","PAYMENT_ATTEMPT",id);return attempt(p.tenant(),id);
    }
    /** Called only before a new launch, under the tenant policy lock. In-flight attempts retain their launch date. */
    String launchBudget(Purchase p,Attempt a,PaymentPolicyService.Policy policy){
        boolean today=Boolean.TRUE.equals(jdbc.queryForObject("select budget_date=(now() at time zone 'UTC')::date from payment_budget_reservations where tenant_id=? and payment_attempt_id=? and state='RESERVED' for update",Boolean.class,p.tenant(),a.id()));
        long extra=today?0:a.amountFen();
        if(policies.used(p.tenant(),null)+extra>Math.min(1000000,policy.maxDailyFen()))return "TENANT_DAILY_LIMIT_EXCEEDED";
        if(policies.used(p.tenant(),p.store())+extra>policy.maxStoreDailyFen())return "STORE_DAILY_LIMIT_EXCEEDED";
        if(!today)jdbc.update("update payment_budget_reservations set budget_date=(now() at time zone 'UTC')::date where tenant_id=? and payment_attempt_id=? and state='RESERVED'",p.tenant(),a.id());
        return null;
    }
    public View request(TenantPrincipal p,UUID id,String key){return tx.execute(status->{Purchase po=scoped(p,id);var policy=policies.lock(p.currentTenantId());Attempt old=latest(po.tenant(),id);frozen(old);return support.command(po.tenant(),key,"PAYMENT_REQUEST",Map.of("purchaseId",id),View.class,()->{if(old==null)create(po,policy,"MANUAL".equals(policy.mode()),p.userId());return view(purchase(po.tenant(),id));});});}
    void plan(UUID tenant,UUID purchase){tx.executeWithoutResult(status->{var policy=policies.lock(tenant);Purchase po=purchase(tenant,purchase);if("MANUAL".equals(policy.mode())||latest(tenant,purchase)!=null)return;try{stores.requireActive(tenant,po.store(),true);create(po,policy,false,null);}catch(com.smartprocure.common.ApiException ex){decision(po,ex.code());audit.object(tenant,null,"MOCK_PAYMENT_BLOCKED","PURCHASE_ORDER",purchase);}});}
    public Entry checkoutEntry(TenantPrincipal p,UUID id,String key){return tx.execute(status->{Purchase po=scoped(p,id);var policy=policies.lock(po.tenant());Attempt old=latest(po.tenant(),id);frozen(old);if(policy.globalPaused()||policy.isPaused())throw M1Support.conflict("PAYMENT_PAUSED");return support.command(po.tenant(),key,"PAYMENT_CHECKOUT",Map.of("purchaseId",id),Entry.class,()->{Attempt a=old;if(a!=null&&Set.of("PAID","FAILED").contains(a.state()))throw M1Support.conflict("PAYMENT_ALREADY_RESOLVED");if(a==null)a=create(po,policy,true,p.userId());else if(!"MANUAL".equals(a.mode())){String reason=localBlock(po,policy,false);if(reason!=null)throw M1Support.conflict(reason);jdbc.update("update payment_attempts set mode='MANUAL',mandate_id=null,version=version+1 where tenant_id=? and id=? and state='NEW'",po.tenant(),a.id());decision(po,"MANUAL_PAYMENT_REQUIRED");audit.object(po.tenant(),p.userId(),"MOCK_PAYMENT_ROUTED_MANUAL","PAYMENT_ATTEMPT",a.id());}
            if(a==null)throw M1Support.conflict(purchase(po.tenant(),id).decision());Instant expiry=expiry(po.tenant(),a.id());if(!expiry.isAfter(Instant.now()))throw M1Support.conflict("CHECKOUT_EXPIRED");return new Entry(id,a.id(),"/payments/mock/"+a.id(),"MOCK",expiry);});});}
    Instant expiry(UUID tenant,UUID attempt){return jdbc.queryForObject("select checkout_expires_at from payment_attempts where tenant_id=? and id=?",(r,n)->r.getTimestamp(1).toInstant(),tenant,attempt);}
    PaymentProviderAdapter.Request providerRequest(Purchase p,Attempt a){return new PaymentProviderAdapter.Request(p.id(),p.account(),p.external(),"MOCK-PAY-"+a.id(),a.amountFen(),a.currency());}
    public Checkout checkout(TenantPrincipal p,UUID attempt){policies.allowed(p.currentTenantId());Attempt a=attempt(p.currentTenantId(),attempt);Purchase po=scoped(p,a.purchaseId());if(!"MANUAL".equals(a.mode()))throw M1Support.missing();frozen(a);var authority=provider.query(po.tenant(),providerRequest(po,a));if(!com.smartprocure.provider.MockPaymentAdapter.matches(providerRequest(po,a),authority))throw M1Support.conflict("PAYMENT_AUTHORITY_MISMATCH");Instant expiry=expiry(po.tenant(),attempt);var policy=policies.get(p);return new Checkout(a,po.id(),po.total(),po.currency(),authority.state(),"NEW".equals(a.state())&&expiry.isAfter(Instant.now())&&!policy.isPaused()&&!policy.globalPaused(),expiry,"MOCK");}
    void enqueue(UUID tenant,UUID attempt,boolean queryOnly){jdbc.update("insert into outbox_events(tenant_id,aggregate_id,event_type,payload) values(?,?,'M3_PAYMENT',jsonb_build_object('queryOnly',?::boolean)) on conflict(tenant_id,aggregate_id) where event_type='M3_PAYMENT' do update set state=case when outbox_events.state='PROCESSING' then outbox_events.state else 'NEW' end,available_at=now(),payload=excluded.payload",tenant,attempt,queryOnly);}
    public View receipt(TenantPrincipal p,UUID attempt,String key,Receipt receipt){
        policies.allowed(p.currentTenantId());
        Attempt before=attempt(p.currentTenantId(),attempt);
        Purchase beforePurchase=scoped(p,before.purchaseId());
        if(!"MANUAL".equals(before.mode()))throw M1Support.missing();
        frozen(before);
        // These read-only authority calls happen outside the mutation transaction, immediately before the launch gate.
        SalesOrderProviderAdapter.Snapshot authority="NEW".equals(before.state())&&receipt.outcome()==Outcome.PAID
                ?sales.authoritativeOrder(beforePurchase.tenant(),beforePurchase.store(),beforePurchase.salesExternal()):null;
        return tx.execute(status->{
            policies.lock(p.currentTenantId());
            Attempt current=attempt(p.currentTenantId(),attempt);
            Purchase po=scoped(p,current.purchaseId());
            frozen(current);
            if("NEW".equals(current.state())&&!expiry(po.tenant(),attempt).isAfter(Instant.now()))throw M1Support.conflict("CHECKOUT_EXPIRED");
            return support.command(po.tenant(),key,"MOCK_PAYMENT_RECEIPT",Map.of("attemptId",attempt,"outcome",receipt.outcome()),View.class,()->{
                if("NEW".equals(current.state())){
                    var policy=policies.get(p);
                    stores.requireActive(po.tenant(),po.store(),true);
                    jdbc.query("select si.id from sales_order_items si join sales_orders o on o.tenant_id=si.tenant_id and o.id=si.sales_order_id where si.tenant_id=? and si.id=? for no key update of si,o",(r,n)->r.getObject(1,UUID.class),po.tenant(),po.item());
                    Purchase fresh=purchase(po.tenant(),po.id());
                    String reason=localBlock(fresh,policy,false);
                    if(reason==null&&receipt.outcome()==Outcome.PAID)reason=salesAuthorityBlock(fresh,authority);
                    if(reason==null&&receipt.outcome()==Outcome.PAID)reason=launchBudget(fresh,current,policy);
                    if(reason!=null)throw M1Support.conflict(reason);
                    if(receipt.outcome()==Outcome.PAID){
                        int n=jdbc.update("update payment_attempts set state='PAYING',started_at=now(),failure_code=null,version=version+1 where tenant_id=? and id=? and version=? and state='NEW'",po.tenant(),attempt,current.version());
                        if(n!=1)throw M1Support.conflict("PAYMENT_STATE_CHANGED");
                        audit.object(po.tenant(),p.userId(),"MOCK_MANUAL_PAYMENT_LAUNCH_COMMITTED","PAYMENT_ATTEMPT",attempt);
                    }
                }
                // This is a local, authenticated simulator mutation, never a callback or real payment API.
                // It participates in this transaction so synthetic authority, audit and query queue commit atomically.
                provider.simulateReceipt(po.tenant(),providerRequest(po,current),receipt.outcome().name());
                enqueue(po.tenant(),attempt,true);
                audit.object(po.tenant(),p.userId(),"MOCK_PAYMENT_RECEIPT_SIMULATED","PAYMENT_ATTEMPT",attempt);
                return view(purchase(po.tenant(),po.id()));
            });
        });
    }
    String salesAuthorityBlock(Purchase p,SalesOrderProviderAdapter.Snapshot authority){
        if(authority==null||!"PAID".equals(authority.sourceStatus())||!p.salesExternal().equals(authority.externalOrderId())||authority.sourceVersion()!=p.sourceVersion()||!p.currency().equals(authority.currency()))return "SALES_AUTHORITY_CHANGED";
        if(p.sourceDigest()!=null&&!p.sourceDigest().equals(M1Support.digest(support.json(authority))))return "SALES_AUTHORITY_CHANGED";
        var item=authority.items().stream().filter(i->i.externalItemId().equals(p.itemExternal())).findFirst();
        if(item.isEmpty()||!item.get().externalSkuId().equals(p.sku())||item.get().qty()-item.get().cancelledQty()-item.get().fulfilledQty()<p.qty())return "SALES_AUTHORITY_CHANGED";
        return null;
    }
    public View reconcile(TenantPrincipal p,UUID id,String key){return tx.execute(status->{Purchase po=scoped(p,id);policies.lock(po.tenant());Attempt a=latest(po.tenant(),id);if(a==null||("NEW".equals(a.state())&&!"MANUAL".equals(a.mode())))throw M1Support.conflict("PAYMENT_NOT_STARTED");return support.command(po.tenant(),key,"PAYMENT_RECONCILE",Map.of("purchaseId",id),View.class,()->{enqueue(po.tenant(),a.id(),true);audit.object(po.tenant(),p.userId(),"MOCK_PAYMENT_QUERY_REQUESTED","PAYMENT_ATTEMPT",a.id());return view(po);});});}
    public Config config(TenantPrincipal p,UUID id){Purchase po=scoped(p,id);return config(po.tenant(),id);}
    private Config config(UUID tenant,UUID id){return jdbc.query("select behavior,version from mock_payment_configs where tenant_id=? and purchase_order_id=?",(r,n)->new Config(id,r.getString(1),r.getLong(2),"MOCK"),tenant,id).stream().findFirst().orElse(new Config(id,"NORMAL",0,"MOCK"));}
    public Config configure(TenantPrincipal p,UUID id,ConfigUpdate u){return tx.execute(status->{Purchase po=scoped(p,id);policies.lock(po.tenant());Attempt a=latest(po.tenant(),id);if(a!=null&&a.startedAt()!=null&&!Set.of("NORMAL","QUERY_TIMEOUT","AUTHORITY_MISMATCH").contains(u.behavior().name()))throw M1Support.conflict("PAYMENT_ALREADY_STARTED");jdbc.update("insert into mock_payment_configs(tenant_id,purchase_order_id) values(?,?) on conflict do nothing",po.tenant(),id);int n=jdbc.update("update mock_payment_configs set behavior=?,version=version+1 where tenant_id=? and purchase_order_id=? and version=? and version<9007199254740991",u.behavior().name(),po.tenant(),id,u.version());if(n!=1)throw M1Support.conflict("VERSION_CONFLICT");audit.object(po.tenant(),p.userId(),"MOCK_PAYMENT_CONFIG_UPDATED","PURCHASE_ORDER",id);return config(po.tenant(),id);});}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public M1Support.Page<Reconciliation> reconciliations(TenantPrincipal p,int limit,int offset,UUID store){policies.allowed(p.currentTenantId());M1Support.page(limit,offset);if(store!=null)stores.get(p,store);List<Object> args=new ArrayList<>();String where=support.scope(p,"s",args);if(store!=null){where+=" and s.id=?";args.add(store);}String joins=" from payment_reconciliations r join purchase_orders po on po.tenant_id=r.tenant_id and po.id=r.purchase_order_id join purchase_intents i on i.tenant_id=po.tenant_id and i.id=po.purchase_intent_id join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id join sales_orders o on o.tenant_id=si.tenant_id and o.id=si.sales_order_id join stores s on s.tenant_id=o.tenant_id and s.id=o.store_id where "+where;long total=jdbc.queryForObject("select count(*)"+joins,Long.class,args.toArray());args.add(limit);args.add(offset);return new M1Support.Page<>(jdbc.query("select r.*"+joins+" order by r.created_at desc,r.id desc limit ? offset ?",(r,n)->new Reconciliation(r.getObject("id",UUID.class),r.getObject("purchase_order_id",UUID.class),r.getObject("payment_attempt_id",UUID.class),r.getString("outcome"),r.getString("local_state"),r.getTimestamp("created_at").toInstant(),"MOCK"),args.toArray()),total,limit,offset);}
}
