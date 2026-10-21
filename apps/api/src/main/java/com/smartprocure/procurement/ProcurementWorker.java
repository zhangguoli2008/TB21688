package com.smartprocure.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.audit.AuditService;
import com.smartprocure.common.ApiException;
import com.smartprocure.common.M1Support;
import com.smartprocure.common.M4Activity;
import com.smartprocure.provider.SalesOrderProviderAdapter;
import com.smartprocure.provider.SupplierProviderAdapter;
import com.smartprocure.sourcing.SourcingService;
import com.smartprocure.store.StoreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** At-least-once Outbox worker, fenced by durable leases and a unique supplier client reference. */
@Service
public class ProcurementWorker {
    private record Job(UUID event,UUID tenant,UUID intent,UUID item,UUID store,String external,int attempts,UUID lease,String state) { }
    private record Saved(UUID account,UUID offer,String reference,int qty,long total,String currency,long mappingVersion,long itemVersion,long sourceVersion,SourcingService.QuoteView quote,ProcurementPolicyService.PolicySnapshot policy) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final ProcurementPlanner planner;
    private final ProcurementRuleEngine engine;
    private final ProcurementService service;
    private final ProcurementPolicyService policies;
    private final SalesOrderProviderAdapter sales;
    private final SupplierProviderAdapter supplier;
    private final StoreService stores;
    private final AuditService audit;
    private final M4Activity activity;
    public ProcurementWorker(JdbcTemplate jdbc,ObjectMapper mapper,org.springframework.transaction.PlatformTransactionManager manager,ProcurementPlanner planner,ProcurementRuleEngine engine,ProcurementService service,ProcurementPolicyService policies,SalesOrderProviderAdapter sales,SupplierProviderAdapter supplier,StoreService stores,AuditService audit,M4Activity activity) { this.jdbc=jdbc;this.mapper=mapper;this.tx=new TransactionTemplate(manager);this.planner=planner;this.engine=engine;this.service=service;this.policies=policies;this.sales=sales;this.supplier=supplier;this.stores=stores;this.audit=audit;this.activity=activity; }
    public void runOnce() {
        stores.requireDemo();planner.runOnce();
        for(int count=0;count<20;count++) {
            Job job=tx.execute(status->claim());if(job==null)return;
            boolean calledCreate=false;
            try {
                if(Set.of("CREATE_UNKNOWN","CREATING").contains(job.state())) {
                    Saved saved=load(job);
                    var result=supplier.query(job.tenant(),saved.account(),saved.reference());
                    tx.executeWithoutResult(status->{if(result.isPresent())complete(job,saved,result.get());else unresolved(job,"CREATE_NOT_FOUND",false);});
                } else {
                    // Authority and fresh supplier quote calls deliberately run outside mutation transactions.
                    var evaluation=engine.evaluate(job.tenant(),job.item());
                    // Read sales authority after quote latency, immediately before the fenced create preparation.
                    var authority=sales.authoritativeOrder(job.tenant(),job.store(),job.external());
                    var request=tx.execute(status->prepare(job,evaluation,authority));
                    if(request==null)continue;
                    calledCreate=true;
                    var result=supplier.create(job.tenant(),request);
                    tx.executeWithoutResult(status->complete(job,load(job),result));
                }
            } catch(RuntimeException ex) {
                boolean sideEffectPossible=calledCreate||Set.of("CREATING","CREATE_UNKNOWN").contains(job.state());
                tx.executeWithoutResult(status->failure(job,ex,sideEffectPossible));
            }
        }
    }
    private Job claim() {
        var rows=jdbc.query("""
            select e.id,e.tenant_id,e.aggregate_id,i.sales_order_item_id,o.store_id,o.external_order_id,e.attempts,i.state
            from outbox_events e join purchase_intents i on i.tenant_id=e.tenant_id and i.id=e.aggregate_id
            join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id
            join sales_orders o on o.tenant_id=si.tenant_id and o.id=si.sales_order_id
            where e.event_type='M2_PURCHASE_CREATE' and ((e.state in ('NEW','RETRY_WAIT') and e.available_at<=now()) or (e.state='PROCESSING' and e.lease_until<now()))
            order by e.available_at,e.id for update of e skip locked limit 1
            """,(r,n)->new Job(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getObject(5,UUID.class),r.getString(6),r.getInt(7)+1,UUID.randomUUID(),r.getString(8)));
        if(rows.isEmpty())return null;Job job=rows.getFirst();
        if(job.attempts()>5) {
            jdbc.update("update outbox_events set state='DEAD',lease_until=null,lease_token=null,last_error_code='WORKER_LEASE_EXHAUSTED' where tenant_id=? and id=?",job.tenant(),job.event());
            if(Set.of("CREATING","CREATE_UNKNOWN").contains(job.state()))setState(job,"CREATE_UNKNOWN","CREATE_UNRESOLVED");
            else setState(job,"REVIEW_REQUIRED","WORKER_LEASE_EXHAUSTED");
            audit.object(job.tenant(),null,"MOCK_PROCUREMENT_DEAD","PURCHASE_INTENT",job.intent());return null;
        }
        jdbc.update("update outbox_events set state='PROCESSING',attempts=?,lease_until=now()+interval '30 seconds',lease_token=? where tenant_id=? and id=?",job.attempts(),job.lease(),job.tenant(),job.event());
        // A persisted CREATING means a call may already have escaped the process. Recovery can only query.
        if("CREATING".equals(job.state()))setState(job,"CREATE_UNKNOWN","CREATE_RECOVERY_REQUIRED");
        return job;
    }
    private <T> T parse(String json,Class<T> type) { try{return mapper.readValue(json,type);}catch(java.io.IOException ex){throw new IllegalStateException(ex);} }
    private Saved load(Job job) {
        return jdbc.queryForObject("select supplier_account_id,supplier_offer_id,client_reference,qty,estimated_total_fen,currency,mapping_version,sales_item_version,sales_source_version,quote_snapshot::text,policy_snapshot::text from purchase_intents where tenant_id=? and id=?",(r,n)->new Saved(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getInt(4),r.getLong(5),r.getString(6),r.getLong(7),r.getLong(8),r.getLong(9),parse(r.getString(10),SourcingService.QuoteView.class),parse(r.getString(11),ProcurementPolicyService.PolicySnapshot.class)),job.tenant(),job.intent());
    }
    private SupplierProviderAdapter.CreateRequest prepare(Job job,ProcurementRuleEngine.Evaluation evaluation,SalesOrderProviderAdapter.Snapshot authority) {
        if(!owns(job))return null;Saved saved=load(job);
        var policy=policies.lock(job.tenant(),job.store());stores.requireActive(job.tenant(),job.store(),true);
        if(!evaluation.allowed()) { review(job,evaluation.reasons().isEmpty()?"PROCUREMENT_BLOCKED":evaluation.reasons().getFirst().code());return null; }
        if(!"PAID".equals(authority.sourceStatus())||authority.sourceVersion()!=saved.sourceVersion()||!job.external().equals(authority.externalOrderId())) { review(job,"SALES_AUTHORITY_CHANGED");return null; }
        var local=jdbc.queryForObject("select i.external_item_id,i.external_sku_id,o.source_digest from sales_order_items i join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where i.tenant_id=? and i.id=?",(r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)},job.tenant(),job.item());
        var authorityItem=authority.items().stream().filter(item->item.externalItemId().equals(local[0])).findFirst();
        if(authorityItem.isEmpty()||!authorityItem.get().externalSkuId().equals(local[1])||authorityItem.get().qty()-authorityItem.get().cancelledQty()-authorityItem.get().fulfilledQty()!=saved.qty()||local[2]!=null&&!local[2].equals(M1Support.digest(json(authority)))) { review(job,"SALES_AUTHORITY_CHANGED");return null; }
        if(evaluation.mappingVersion()!=saved.mappingVersion()||evaluation.salesItemVersion()!=saved.itemVersion()||evaluation.salesSourceVersion()!=saved.sourceVersion()||!ProcurementService.sameQuote(saved.quote(),evaluation.selectedQuote())) { review(job,"QUOTE_CHANGED");return null; }
        if(policy.tenantVersion()!=saved.policy().tenantVersion()||policy.storeVersion()!=saved.policy().storeVersion()) { review(job,"POLICY_CHANGED");return null; }
        service.validateLocked(job.tenant(),evaluation,policy,saved.quote(),false);
        UUID purchase=UUID.randomUUID();
        jdbc.update("insert into purchase_orders(id,tenant_id,purchase_intent_id,supplier_account_id,state,total_fen,currency,client_reference) values(?,?,?,?,'CREATING',?,?,?) on conflict(tenant_id,purchase_intent_id) do nothing",purchase,job.tenant(),job.intent(),saved.account(),saved.total(),saved.currency(),saved.reference());
        int changed=jdbc.update("update purchase_intents set state='CREATING',failure_code=null,version=version+1,updated_at=now() where tenant_id=? and id=? and state='APPROVED'",job.tenant(),job.intent());
        if(changed!=1)throw M1Support.conflict("INTENT_STATE_CHANGED");
        audit.object(job.tenant(),null,"MOCK_PURCHASE_CREATE_STARTED","PURCHASE_INTENT",job.intent());
        return new SupplierProviderAdapter.CreateRequest(saved.account(),saved.offer(),saved.reference(),saved.qty(),saved.total(),saved.currency(),saved.quote().offerVersion(),saved.quote().expiresAt());
    }
    private String json(Object value) { try{return mapper.writeValueAsString(value);}catch(java.io.IOException ex){throw new IllegalStateException(ex);} }
    private boolean owns(Job job) { return !jdbc.query("select id from outbox_events where tenant_id=? and id=? and state='PROCESSING' and lease_token=? and lease_until>now() for update",(r,n)->r.getObject(1,UUID.class),job.tenant(),job.event(),job.lease()).isEmpty(); }
    private void complete(Job job,Saved saved,SupplierProviderAdapter.Purchase result) {
        if(!owns(job))return;
        if(!saved.account().equals(result.supplierAccountId())||!saved.reference().equals(result.clientReference())||saved.total()!=result.totalFen()||!saved.currency().equals(result.currency())||!"AWAITING_PAYMENT".equals(result.state())||result.externalOrderId()==null||!result.externalOrderId().startsWith("MOCK-PURCHASE-"))throw M1Support.conflict("SUPPLIER_AUTHORITY_MISMATCH");
        jdbc.update("update purchase_orders set state='AWAITING_PAYMENT',external_order_id=?,version=version+1,updated_at=now() where tenant_id=? and purchase_intent_id=? and client_reference=?",result.externalOrderId(),job.tenant(),job.intent(),saved.reference());
        setState(job,"AWAITING_PAYMENT",null);finish(job,"DONE",null);
        audit.object(job.tenant(),null,"MOCK_PURCHASE_AWAITING_PAYMENT","PURCHASE_INTENT",job.intent());
    }
    private void setState(Job job,String state,String failure) {
        UUID order=jdbc.queryForObject("select sales_order_id from sales_order_items where tenant_id=? and id=?",UUID.class,job.tenant(),job.item());
        activity.record(job.tenant(),order,null,"PROCUREMENT","MOCK_PURCHASE_"+state,"PURCHASE_INTENT",job.intent(),failure,"intent:"+job.intent()+":"+state,false);
        jdbc.update("update purchase_intents set state=?,failure_code=?,version=version+1,updated_at=now() where tenant_id=? and id=?",state,failure,job.tenant(),job.intent());
        jdbc.update("update purchase_orders set state=?,version=version+1,updated_at=now() where tenant_id=? and purchase_intent_id=?",state,job.tenant(),job.intent());
    }
    private void review(Job job,String code) { setState(job,"REVIEW_REQUIRED",code);finish(job,"BLOCKED",code);audit.object(job.tenant(),null,"MOCK_PROCUREMENT_REVIEW_REQUIRED","PURCHASE_INTENT",job.intent()); }
    private void unresolved(Job job,String code,boolean retry) { if(!owns(job))return;if(!retry)review(job,code);else{setState(job,"CREATE_UNKNOWN",code);retry(job,code);} }
    private void finish(Job job,String state,String code) { int changed=jdbc.update("update outbox_events set state=?,published_at=case when ?='DONE' then now() else published_at end,lease_until=null,lease_token=null,last_error_code=? where tenant_id=? and id=? and lease_token=?",state,state,code,job.tenant(),job.event(),job.lease());if(changed!=1)throw M1Support.conflict("WORKER_LEASE_LOST"); }
    private void retry(Job job,String code) {
        if(job.attempts()>=5) { finish(job,"DEAD",code);audit.object(job.tenant(),null,"MOCK_PROCUREMENT_DEAD","PURCHASE_INTENT",job.intent());return; }
        int delay=Math.min(60,1<<job.attempts());
        int changed=jdbc.update("update outbox_events set state='RETRY_WAIT',available_at=now()+(?*interval '1 second'),lease_until=null,lease_token=null,last_error_code=? where tenant_id=? and id=? and lease_token=?",delay,code,job.tenant(),job.event(),job.lease());
        if(changed!=1)throw M1Support.conflict("WORKER_LEASE_LOST");
    }
    private void failure(Job job,RuntimeException ex,boolean possible) {
        if(!owns(job))return;String code=ex instanceof ApiException api?api.code():"SUPPLIER_TEMPORARILY_UNAVAILABLE";
        if(possible) { setState(job,"CREATE_UNKNOWN",code);retry(job,code);audit.object(job.tenant(),null,"MOCK_PURCHASE_CREATE_UNKNOWN","PURCHASE_INTENT",job.intent()); }
        else if(Set.of("SUPPLIER_TIMEOUT","AUTHORITY_NOT_FOUND").contains(code)&&job.attempts()<5)retry(job,code);
        else review(job,code);
    }
}
