package com.smartprocure.sales;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.ApiException;
import com.smartprocure.common.M1Support;
import com.smartprocure.common.M4Activity;
import com.smartprocure.provider.SalesOrderProviderAdapter;
import com.smartprocure.store.StoreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Durable DB Outbox consumer: at least once, leased claims, bounded retries and DEAD state. */
@Service
public class SalesEventWorker {
    private record Job(UUID id,UUID tenant,UUID store,UUID inbox,String event,String external,long sourceVersion,int attempts,UUID lease) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SalesOrderProviderAdapter provider;
    private final StoreService stores;
    private final M1Support support;
    private final AuditService audit;
    private final M4Activity activity;
    public SalesEventWorker(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager transactionManager,SalesOrderProviderAdapter provider,StoreService stores,M1Support support,AuditService audit,M4Activity activity) { this.jdbc=jdbc;this.tx=new TransactionTemplate(transactionManager);this.provider=provider;this.stores=stores;this.support=support;this.audit=audit;this.activity=activity; }
    public void runOnce() {
        stores.requireDemo();
        for(int count=0;count<20;count++) {
            Job job=tx.execute(status->claim());if(job==null)return;
            try {
                // Provider lookup intentionally runs outside the local mutation transaction.
                var snapshot=provider.authoritativeOrder(job.tenant(),job.store(),job.external());
                tx.executeWithoutResult(status->apply(job,snapshot));
            } catch(RuntimeException ex) { tx.executeWithoutResult(status->failure(job,ex)); }
        }
    }
    private Job claim() {
        // This control-plane scan only claims identifiers. All business reads/writes below include tenant_id.
        var rows=jdbc.query("""
            select o.id,o.tenant_id,o.aggregate_id,o.inbox_id,i.event_id,i.external_order_id,i.source_version,o.attempts
            from outbox_events o join inbox_events i on i.tenant_id=o.tenant_id and i.id=o.inbox_id
            where o.event_type='M1_SALES_SYNC' and ((o.state in ('NEW','RETRY_WAIT') and o.available_at<=now()) or (o.state='PROCESSING' and o.lease_until<now()))
            order by o.available_at,o.id for update of o skip locked limit 1
            """,(r,n)->new Job(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getString(5),r.getString(6),r.getLong(7),r.getInt(8)+1,UUID.randomUUID()));
        if(rows.isEmpty())return null;Job job=rows.getFirst();
        // A crash after the fifth claim must not create an unbounded sixth provider attempt.
        if(job.attempts()>5) {
            jdbc.update("update outbox_events set state='DEAD',lease_until=null,lease_token=null,last_error_code='WORKER_LEASE_EXHAUSTED' where tenant_id=? and id=?",job.tenant(),job.id());
            jdbc.update("update inbox_events set state='DEAD',processed_at=now() where tenant_id=? and id=?",job.tenant(),job.inbox());
            audit.object(job.tenant(),null,"MOCK_SALES_SYNC_DEAD","OUTBOX",job.id());return null;
        }
        jdbc.update("update outbox_events set state='PROCESSING',attempts=?,lease_until=now()+interval '30 seconds',lease_token=? where tenant_id=? and id=?",job.attempts(),job.lease(),job.tenant(),job.id());return job;
    }
    private void apply(Job job,SalesOrderProviderAdapter.Snapshot snapshot) {
        if(!ownsLease(job))return;
        stores.requireActive(job.tenant(),job.store(),true);
        validate(job,snapshot);
        var existing=jdbc.query("select id,source_version,source_digest,version from sales_orders where tenant_id=? and store_id=? and external_order_id=? for update",(r,n)->new Object[]{r.getObject(1,UUID.class),r.getLong(2),r.getString(3),r.getLong(4)},job.tenant(),job.store(),job.external());
        String digest=M1Support.digest(support.json(snapshot));UUID order;
        String action="AUTHORITY_APPLIED";
        if(existing.isEmpty()) {
            order=UUID.randomUUID();jdbc.update("insert into sales_orders(id,tenant_id,store_id,external_order_id,source_status,local_status,currency,paid_amount_fen,paid_at,promised_ship_at,source_updated_at,source_version,source_digest) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",order,job.tenant(),job.store(),job.external(),snapshot.sourceStatus(),local(snapshot.sourceStatus()),snapshot.currency(),snapshot.paidAmountFen(),timestamp(snapshot.paidAt()),timestamp(snapshot.promisedShipAt()),timestamp(snapshot.sourceUpdatedAt()),snapshot.sourceVersion(),digest);
            applyItems(job.tenant(),order,snapshot);
        } else {
            Object[] old=existing.getFirst();order=(UUID)old[0];long version=(long)old[1];
            if(version==snapshot.sourceVersion()&&!Objects.equals(old[2],digest))throw M1Support.conflict("AUTHORITY_VERSION_CONFLICT");
            if(version>=snapshot.sourceVersion())action=version>snapshot.sourceVersion()?"STALE_AUTHORITY_IGNORED":"AUTHORITY_UNCHANGED";
            else {
                int changed=jdbc.update("update sales_orders set source_status=?,local_status=?,currency=?,paid_amount_fen=?,paid_at=?,promised_ship_at=?,source_updated_at=?,source_version=?,source_digest=?,updated_at=now(),version=version+1 where tenant_id=? and id=? and version=?",snapshot.sourceStatus(),local(snapshot.sourceStatus()),snapshot.currency(),snapshot.paidAmountFen(),timestamp(snapshot.paidAt()),timestamp(snapshot.promisedShipAt()),timestamp(snapshot.sourceUpdatedAt()),snapshot.sourceVersion(),digest,job.tenant(),order,(long)old[3]);
                if(changed!=1)throw M1Support.conflict("VERSION_CONFLICT");applyItems(job.tenant(),order,snapshot);
            }
        }
        jdbc.update("insert into sales_order_timeline(tenant_id,sales_order_id,event_id,action,source_version) values(?,?,?,?,?) on conflict do nothing",job.tenant(),order,job.event(),action,snapshot.sourceVersion());
        jdbc.update("update inbox_events set state='PROCESSED',processed_at=now() where tenant_id=? and id=?",job.tenant(),job.inbox());
        int completed=jdbc.update("update outbox_events set state='DONE',published_at=now(),lease_until=null,lease_token=null,last_error_code=null where tenant_id=? and id=? and state='PROCESSING' and lease_token=?",job.tenant(),job.id(),job.lease());
        if(completed!=1)throw M1Support.conflict("WORKER_LEASE_LOST");
        audit.object(job.tenant(),null,"MOCK_SALES_AUTHORITY_VERIFIED","SALES_ORDER",order);
        if("AUTHORITY_APPLIED".equals(action))activity.record(job.tenant(),order,null,"SALES","MOCK_SALES_UPDATED","SALES_ORDER",order,snapshot.sourceStatus(),"sales:"+order+":"+snapshot.sourceVersion(),false);
    }
    private void applyItems(UUID tenant,UUID order,SalesOrderProviderAdapter.Snapshot snapshot) {
        // Purchased items are durable evidence. Missing authority items become non-procurable instead of disappearing.
        var ids=snapshot.items().stream().map(SalesOrderProviderAdapter.Item::externalItemId).toList();
        for(var item:snapshot.items())jdbc.update("insert into sales_order_items(tenant_id,sales_order_id,external_item_id,external_sku_id,title,qty,paid_allocated_fen,cancelled_qty,fulfilled_qty) values(?,?,?,?,?,?,?,?,?) on conflict(tenant_id,sales_order_id,external_item_id) do update set external_sku_id=excluded.external_sku_id,title=excluded.title,qty=excluded.qty,paid_allocated_fen=excluded.paid_allocated_fen,cancelled_qty=excluded.cancelled_qty,fulfilled_qty=excluded.fulfilled_qty,version=sales_order_items.version+1",tenant,order,item.externalItemId(),item.externalSkuId(),item.title(),item.qty(),item.paidAllocatedFen(),item.cancelledQty(),item.fulfilledQty());
        List<Object> params=new ArrayList<>();params.add(tenant);params.add(order);params.addAll(ids);
        String missing="tenant_id=? and sales_order_id=? and external_item_id not in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";
        jdbc.update("update sales_order_items i set cancelled_qty=qty-fulfilled_qty,version=version+1 where "+missing+" and (exists(select 1 from purchase_intents p where p.tenant_id=i.tenant_id and p.sales_order_item_id=i.id) or exists(select 1 from aftersales_cases c where c.tenant_id=i.tenant_id and c.sales_order_item_id=i.id) or exists(select 1 from mock_aftersale_authority a where a.tenant_id=i.tenant_id and a.sales_order_id=i.sales_order_id and a.snapshot->>'salesOrderItemId'=i.id::text))",params.toArray());
        jdbc.update("delete from sales_order_items i where "+missing+" and not exists(select 1 from purchase_intents p where p.tenant_id=i.tenant_id and p.sales_order_item_id=i.id) and not exists(select 1 from aftersales_cases c where c.tenant_id=i.tenant_id and c.sales_order_item_id=i.id) and not exists(select 1 from mock_aftersale_authority a where a.tenant_id=i.tenant_id and a.sales_order_id=i.sales_order_id and a.snapshot->>'salesOrderItemId'=i.id::text)",params.toArray());
    }
    /** Fence the complete apply transaction; competing claimers use SKIP LOCKED. */
    private boolean ownsLease(Job job) { return !jdbc.query("select id from outbox_events where tenant_id=? and id=? and state='PROCESSING' and lease_token=? and lease_until>now() for update",(r,n)->r.getObject(1,UUID.class),job.tenant(),job.id(),job.lease()).isEmpty(); }
    private void failure(Job job,RuntimeException ex) {
        String code=ex instanceof ApiException api?api.code():"AUTHORITY_TEMPORARILY_UNAVAILABLE";
        boolean blocked=Set.of("AUTHORIZATION_UNAVAILABLE","STORE_UNAVAILABLE","CAPABILITY_UNAVAILABLE").contains(code);
        String state=blocked?"BLOCKED":job.attempts()>=5?"DEAD":"RETRY_WAIT";
        int base=Math.min(60,1<<job.attempts());
        int delay=Math.min(60,base+java.util.concurrent.ThreadLocalRandom.current().nextInt(Math.max(1,base/4)+1));
        int changed=jdbc.update("update outbox_events set state=?,available_at=now()+(?*interval '1 second'),lease_until=null,lease_token=null,last_error_code=? where tenant_id=? and id=? and lease_token=?",state,delay,code,job.tenant(),job.id(),job.lease());
        if(changed>0) {
            if(!state.equals("RETRY_WAIT"))jdbc.update("update inbox_events set state=?,processed_at=now() where tenant_id=? and id=?",state,job.tenant(),job.inbox());
            audit.object(job.tenant(),null,"MOCK_SALES_SYNC_"+state,"OUTBOX",job.id());
        }
    }
    private void validate(Job job,SalesOrderProviderAdapter.Snapshot s) {
        if(!s.externalOrderId().equals(job.external())||s.sourceVersion()<job.sourceVersion())throw M1Support.conflict("AUTHORITY_BEHIND");
        if(!Set.of("UNPAID","PAID","CANCELLED","REFUNDING","REFUNDED").contains(s.sourceStatus())||!"CNY".equals(s.currency())||s.paidAmountFen()<0||s.sourceUpdatedAt()==null||s.items()==null||s.items().isEmpty()||s.items().size()>100)throw M1Support.conflict("AUTHORITY_INVALID");
        long sum=0;Set<String> ids=new HashSet<>();
        try { for(var item:s.items()) { if(item.externalItemId()==null||!ids.add(item.externalItemId())||item.externalSkuId()==null||item.title()==null||item.title().length()>400||item.qty()<1||item.paidAllocatedFen()<0||item.cancelledQty()<0||item.fulfilledQty()<0||(long)item.cancelledQty()+item.fulfilledQty()>item.qty())throw M1Support.conflict("AUTHORITY_INVALID");sum=Math.addExact(sum,item.paidAllocatedFen()); } }
        catch(ArithmeticException ex) { throw M1Support.conflict("AUTHORITY_INVALID"); }
        if(sum!=s.paidAmountFen())throw M1Support.conflict("AUTHORITY_INVALID");
    }
    private static String local(String status) { return switch(status) { case "PAID"->"VERIFIED_PAID";case "CANCELLED"->"CANCELLED";case "REFUNDING","REFUNDED"->"SUSPENDED";default->"NEW"; }; }
    private static Timestamp timestamp(Instant instant) { return instant==null?null:Timestamp.from(instant); }
}
