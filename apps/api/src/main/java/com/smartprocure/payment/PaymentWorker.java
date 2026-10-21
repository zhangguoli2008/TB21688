package com.smartprocure.payment;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.ApiException;
import com.smartprocure.common.M1Support;
import com.smartprocure.provider.PaymentProviderAdapter;
import com.smartprocure.provider.MockPaymentAdapter;
import com.smartprocure.provider.SalesOrderProviderAdapter;
import com.smartprocure.store.StoreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Durable PAYING is the launch linearization point. Every recovered launch is query-only. */
@Service
public class PaymentWorker {
    private record Job(UUID event,UUID tenant,UUID attempt,UUID purchase,UUID lease,boolean queryOnly) { }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final PaymentService service;private final PaymentPolicyService policies;private final PaymentProviderAdapter provider;private final SalesOrderProviderAdapter sales;private final StoreService stores;private final AuditService audit;
    private UUID cursor;
    public PaymentWorker(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,PaymentService service,PaymentPolicyService policies,PaymentProviderAdapter provider,SalesOrderProviderAdapter sales,StoreService stores,AuditService audit){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.service=service;this.policies=policies;this.provider=provider;this.sales=sales;this.stores=stores;this.audit=audit;}
    public void runOnce(){stores.requireDemo();plan();for(int i=0;i<20;i++){Job job=tx.execute(s->claim());if(job==null)return;boolean launched=false;boolean query=false;try{
        var p=service.purchase(job.tenant(),job.purchase());var a=service.attempt(job.tenant(),job.attempt());var request=service.providerRequest(p,a);
        if("NEW".equals(a.state())&&!"MANUAL".equals(a.mode())&&!job.queryOnly()){
            // Mock purchase and sales authorities are read after queue delay, outside every local transaction.
            var purchaseAuthority=provider.purchase(job.tenant(),request);
            var salesAuthority=sales.authoritativeOrder(job.tenant(),p.store(),p.salesExternal());
            var ready=tx.execute(s->prepare(job,purchaseAuthority,salesAuthority));if(ready==null)continue;
            launched=true;provider.pay(job.tenant(),ready);request=ready;
        }
        query=true;var result=provider.query(job.tenant(),request);tx.executeWithoutResult(s->apply(job,result));
    }catch(RuntimeException ex){boolean possible=launched;boolean queried=query;tx.executeWithoutResult(s->failure(job,ex,possible,queried));}}}
    private synchronized void plan(){var args=new ArrayList<Object>();String after="";if(cursor!=null){after=" and po.id>?";args.add(cursor);}var rows=jdbc.query("select po.tenant_id,po.id from purchase_orders po join payment_policies pp on pp.tenant_id=po.tenant_id join tenants t on t.id=po.tenant_id where t.status='ACTIVE' and po.state='AWAITING_PAYMENT' and pp.mode in ('AUTO','RULE_BASED') and not pp.is_paused and not exists(select 1 from payment_attempts a where a.tenant_id=po.tenant_id and a.purchase_order_id=po.id)"+after+" order by po.id limit 20",(r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)},args.toArray());if(rows.isEmpty()){cursor=null;return;}for(var row:rows){cursor=row[1];try{service.plan(row[0],row[1]);}catch(ApiException ex){if(!Set.of("CAPABILITY_UNAVAILABLE","AUTHORIZATION_UNAVAILABLE","STORE_UNAVAILABLE","TENANT_UNAVAILABLE").contains(ex.code()))throw ex;}}}
    private Job claim(){var rows=jdbc.query("""
       select e.id,e.tenant_id,e.aggregate_id,a.purchase_order_id,coalesce((e.payload->>'queryOnly')::boolean,false) query_only
       from outbox_events e join payment_attempts a on a.tenant_id=e.tenant_id and a.id=e.aggregate_id
       where e.event_type='M3_PAYMENT' and ((e.state in ('NEW','RETRY_WAIT') and e.available_at<=now()) or (e.state='PROCESSING' and e.lease_until<now()))
       order by e.available_at,e.id for update of e skip locked limit 1
       """,(r,n)->new Job(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getObject(4,UUID.class),UUID.randomUUID(),r.getBoolean(5)));if(rows.isEmpty())return null;var j=rows.getFirst();jdbc.update("update outbox_events set state='PROCESSING',attempts=attempts+1,lease_until=now()+interval '30 seconds',lease_token=? where tenant_id=? and id=?",j.lease(),j.tenant(),j.event());return j;}
    private boolean owns(Job j){return !jdbc.query("select id from outbox_events where tenant_id=? and id=? and state='PROCESSING' and lease_token=? and lease_until>now() for update",(r,n)->r.getObject(1,UUID.class),j.tenant(),j.event(),j.lease()).isEmpty();}
    private PaymentProviderAdapter.Request prepare(Job j,PaymentProviderAdapter.Snapshot purchaseAuthority,SalesOrderProviderAdapter.Snapshot authority){var policy=policies.lock(j.tenant());if(!owns(j))return null;var a=service.attempt(j.tenant(),j.attempt());if(!"NEW".equals(a.state())||"MANUAL".equals(a.mode()))return null;var p=service.purchase(j.tenant(),j.purchase());stores.requireActive(j.tenant(),p.store(),true);
        // Serialize current mapping, offer and local sales changes through the launch transaction.
        jdbc.query("select m.id from sku_mappings m join supplier_offers f on f.tenant_id=m.tenant_id and f.id=m.supplier_offer_id join supplier_accounts sa on sa.tenant_id=f.tenant_id and sa.id=f.supplier_account_id where m.tenant_id=? and m.id=? for no key update of m,f,sa",(r,n)->r.getObject(1,UUID.class),j.tenant(),p.mapping());
        jdbc.query("select si.id from sales_order_items si join sales_orders o on o.tenant_id=si.tenant_id and o.id=si.sales_order_id where si.tenant_id=? and si.id=? for no key update of si,o",(r,n)->r.getObject(1,UUID.class),j.tenant(),p.item());p=service.purchase(j.tenant(),j.purchase());String reason=service.localBlock(p,policy,true);if(reason==null&&(a.amountFen()!=p.total()||!a.currency().equals(p.currency())))reason="PAYMENT_AMOUNT_CHANGED";
        if(reason==null&&(!policy.mode().equals(a.mode())||policy.version()!=a.policyVersion()))reason="POLICY_CHANGED";
        if(reason==null&&!Objects.equals(a.mandateId(),service.mandate(p)))reason="MANDATE_UNAVAILABLE";
        if(reason==null&&(!MockPaymentAdapter.matches(service.providerRequest(p,a),purchaseAuthority)||!"UNPAID".equals(purchaseAuthority.state())))reason="PAYMENT_AUTHORITY_MISMATCH";
        if(reason==null)reason=service.salesAuthorityBlock(p,authority);
        if(reason==null)reason=service.launchBudget(p,a,policy);
        if(reason!=null){routeManual(j,p,a,reason);return null;}
        int n=jdbc.update("update payment_attempts set state='PAYING',started_at=now(),failure_code=null,version=version+1 where tenant_id=? and id=? and state='NEW' and version=?",j.tenant(),j.attempt(),a.version());if(n!=1)throw M1Support.conflict("PAYMENT_STATE_CHANGED");audit.object(j.tenant(),null,"MOCK_PAYMENT_LAUNCH_COMMITTED","PAYMENT_ATTEMPT",j.attempt());return service.providerRequest(p,a);
    }
    private void routeManual(Job j,PaymentService.Purchase p,PaymentService.Attempt a,String code){int n=jdbc.update("update payment_attempts set mode='MANUAL',mandate_id=null,failure_code=?,version=version+1 where tenant_id=? and id=? and state='NEW' and version=?",code,j.tenant(),a.id(),a.version());if(n!=1)throw M1Support.conflict("PAYMENT_STATE_CHANGED");service.decision(p,code);finish(j,"BLOCKED",code);audit.object(j.tenant(),null,"MOCK_PAYMENT_ROUTED_MANUAL","PAYMENT_ATTEMPT",j.attempt());}
    private void finish(Job j,String state,String code){int n=jdbc.update("update outbox_events set state=?,published_at=case when ?='DONE' then now() else published_at end,lease_until=null,lease_token=null,last_error_code=? where tenant_id=? and id=? and lease_token=? and state='PROCESSING'",state,state,code,j.tenant(),j.event(),j.lease());if(n!=1)throw M1Support.conflict("WORKER_LEASE_LOST");}
    private void retry(Job j,String code,int count){if(count>=5){finish(j,"DEAD",code);return;}int delay=Math.min(60,1<<Math.min(count+1,5));int n=jdbc.update("update outbox_events set state='RETRY_WAIT',payload=jsonb_build_object('queryOnly',true),available_at=now()+(?*interval '1 second'),lease_until=null,lease_token=null,last_error_code=? where tenant_id=? and id=? and lease_token=? and state='PROCESSING'",delay,code,j.tenant(),j.event(),j.lease());if(n!=1)throw M1Support.conflict("WORKER_LEASE_LOST");}
    private int count(Job j){return jdbc.queryForObject("select reconciliation_count from payment_attempts where tenant_id=? and id=?",Integer.class,j.tenant(),j.attempt());}
    private void reconcileRecord(Job j,String outcome,String local){jdbc.update("insert into payment_reconciliations(tenant_id,purchase_order_id,payment_attempt_id,outcome,local_state) values(?,?,?,?,?)",j.tenant(),j.purchase(),j.attempt(),outcome,local);audit.object(j.tenant(),null,"MOCK_PAYMENT_AUTHORITY_QUERIED","PAYMENT_ATTEMPT",j.attempt());}
    private void apply(Job j,PaymentProviderAdapter.Snapshot result){policies.lock(j.tenant());if(!owns(j))return;var p=service.purchase(j.tenant(),j.purchase());var a=service.attempt(j.tenant(),j.attempt());var request=service.providerRequest(p,a);boolean matches=MockPaymentAdapter.matches(request,result)&&Set.of("UNPAID","PAID","FAILED").contains(result.state())&&(!"PAID".equals(result.state())||(result.paymentReference()!=null&&result.paymentReference().startsWith("MOCK-PAYMENT-")));
        if(Set.of("PAID","FAILED").contains(a.state())){reconcileRecord(j,matches?result.state():"MISMATCH",a.state());finish(j,"DONE",matches?null:"PAYMENT_AUTHORITY_MISMATCH");return;}
        if(!matches){unknown(j,a,"PAYMENT_AUTHORITY_MISMATCH",true,"MISMATCH");return;}
        if("UNPAID".equals(result.state())){if("NEW".equals(a.state())&&"MANUAL".equals(a.mode())){jdbc.update("update payment_attempts set last_verified_at=now(),version=version+1 where tenant_id=? and id=? and version=?",j.tenant(),j.attempt(),a.version());reconcileRecord(j,"UNPAID",a.state());finish(j,"DONE",null);}else unknown(j,a,"PAYMENT_NOT_CONFIRMED",true,"UNPAID");return;}
        String state=result.state();int n=jdbc.update("update payment_attempts set state=?,provider_payment_reference=?,failure_code=?,resolved_at=now(),last_verified_at=now(),reconciliation_count=reconciliation_count+1,version=version+1 where tenant_id=? and id=? and version=? and state in ('NEW','PAYING','UNKNOWN','MANUAL_RECONCILIATION')",state,result.paymentReference(),"FAILED".equals(state)?"PAYMENT_DECLINED":null,j.tenant(),j.attempt(),a.version());if(n!=1)throw M1Support.conflict("PAYMENT_STATE_CHANGED");
        jdbc.update("update payment_budget_reservations set state=? where tenant_id=? and payment_attempt_id=?","PAID".equals(state)?"CONSUMED":"RELEASED",j.tenant(),j.attempt());
        if("PAID".equals(state)){jdbc.update("update purchase_orders set state='PAID',payment_decision_code=null,version=version+1,updated_at=now() where tenant_id=? and id=?",j.tenant(),j.purchase());jdbc.update("update purchase_intents set state='PAID',version=version+1,updated_at=now() where tenant_id=? and id=?",j.tenant(),p.intent());}
        reconcileRecord(j,state,state);finish(j,"DONE",null);audit.object(j.tenant(),null,"MOCK_PAYMENT_"+state,"PAYMENT_ATTEMPT",j.attempt());
    }
    private void unknown(Job j,PaymentService.Attempt a,String code,boolean queried,String outcome){int c=count(j)+(queried?1:0);String next=c>=5?"MANUAL_RECONCILIATION":"UNKNOWN";int n=jdbc.update("update payment_attempts set state=?,failure_code=?,reconciliation_count=?,last_verified_at=case when ? then now() else last_verified_at end,version=version+1 where tenant_id=? and id=? and version=? and state not in ('PAID','FAILED')",next,code,c,queried,j.tenant(),j.attempt(),a.version());if(n!=1)throw M1Support.conflict("PAYMENT_STATE_CHANGED");if(queried)reconcileRecord(j,outcome,next);retry(j,code,c);audit.object(j.tenant(),null,"MOCK_PAYMENT_FROZEN","PAYMENT_ATTEMPT",j.attempt());}
    private void failure(Job j,RuntimeException ex,boolean launched,boolean queried){policies.lock(j.tenant());if(!owns(j))return;var a=service.attempt(j.tenant(),j.attempt());String code=ex instanceof ApiException api?api.code():"PAYMENT_TEMPORARILY_UNAVAILABLE";
        if(launched||PaymentService.FROZEN.contains(a.state()))unknown(j,a,code,queried,"UNKNOWN");else if("NEW".equals(a.state())&&"MANUAL".equals(a.mode())){reconcileRecord(j,"UNKNOWN",a.state());finish(j,"BLOCKED",code);}else if("NEW".equals(a.state()))routeManual(j,service.purchase(j.tenant(),j.purchase()),a,code);else finish(j,"DONE",code);
    }
    /** Five-minute sweep only queues read-only queries; it never resets a launch state. */
    public void reconcileOverdue(){stores.requireDemo();var rows=jdbc.query("select tenant_id,id from payment_attempts where state in ('PAYING','UNKNOWN') and coalesce(last_verified_at,started_at,created_at)<now()-interval '5 minutes' order by created_at,id limit 100",(r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)});for(var row:rows)tx.executeWithoutResult(s->{policies.lock(row[0]);service.enqueue(row[0],row[1],true);audit.object(row[0],null,"MOCK_PAYMENT_PERIODIC_QUERY_QUEUED","PAYMENT_ATTEMPT",row[1]);});}
}
