package com.smartprocure.provider;

import com.smartprocure.common.ApiException;
import com.smartprocure.common.M1Support;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** A separate durable Mock authority transaction represents the synthetic external side effect. */
@Component
public class MockPaymentAdapter implements PaymentProviderAdapter {
    private final JdbcTemplate jdbc;private final RealChannelGuard guard;private final TransactionTemplate tx;
    public MockPaymentAdapter(JdbcTemplate jdbc,RealChannelGuard guard,org.springframework.transaction.PlatformTransactionManager manager){this.jdbc=jdbc;this.guard=guard;this.tx=new TransactionTemplate(manager);}
    @Override public String provider(){return "MOCK_PAYMENT";}
    @Override public Set<Capability> capabilities(){return Set.of(Capability.MOCK_PAYMENT_PAY,Capability.MOCK_PAYMENT_QUERY);}
    private void allowed(UUID tenant){if(!guard.demoEnabled())throw M1Support.missing();guard.checkDatabaseFlags(tenant);if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tenants where id=? and status='ACTIVE')",Boolean.class,tenant)))throw M1Support.conflict("TENANT_UNAVAILABLE");}
    private String behavior(UUID tenant,UUID purchase){return jdbc.query("select behavior from mock_payment_configs where tenant_id=? and purchase_order_id=?",(r,n)->r.getString(1),tenant,purchase).stream().findFirst().orElse("NORMAL");}
    @Override public Snapshot purchase(UUID tenant,Request request){allowed(tenant);return jdbc.query("select a.supplier_account_id,a.external_order_id,a.total_fen,a.currency from mock_purchase_authority a join purchase_orders p on p.tenant_id=a.tenant_id and p.supplier_account_id=a.supplier_account_id and p.client_reference=a.client_reference where p.tenant_id=? and p.id=?",(r,n)->new Snapshot(request.purchaseId(),r.getObject(1,UUID.class),r.getString(2),r.getLong(3),r.getString(4),"UNPAID",null),tenant,request.purchaseId()).stream().findFirst().orElseThrow(()->M1Support.conflict("PURCHASE_AUTHORITY_NOT_FOUND"));}
    private Optional<Snapshot> stored(UUID tenant,UUID purchase){return jdbc.query("select * from mock_payment_authority where tenant_id=? and purchase_order_id=?",(r,n)->new Snapshot(purchase,r.getObject("supplier_account_id",UUID.class),r.getString("external_order_id"),r.getLong("amount_fen"),r.getString("currency"),r.getString("state"),r.getString("payment_reference")),tenant,purchase).stream().findFirst();}
    private void initialize(UUID tenant,Request r){
        Snapshot authority=purchase(tenant,r);if(!matches(r,authority))throw M1Support.conflict("PAYMENT_AUTHORITY_MISMATCH");
        jdbc.update("insert into mock_payment_authority(tenant_id,purchase_order_id,supplier_account_id,external_order_id,provider_key,amount_fen,currency,state) values(?,?,?,?,?,?,?,'UNPAID') on conflict do nothing",tenant,r.purchaseId(),r.supplierAccountId(),r.externalOrderId(),r.providerKey(),r.amountFen(),r.currency());
        String key=jdbc.queryForObject("select provider_key from mock_payment_authority where tenant_id=? and purchase_order_id=? for update",String.class,tenant,r.purchaseId());if(!r.providerKey().equals(key)||!matches(r,stored(tenant,r.purchaseId()).orElseThrow()))throw M1Support.conflict("PAYMENT_AUTHORITY_MISMATCH");
    }
    public static boolean matches(Request r,Snapshot s){return r.purchaseId().equals(s.purchaseId())&&r.supplierAccountId().equals(s.supplierAccountId())&&Objects.equals(r.externalOrderId(),s.externalOrderId())&&r.amountFen()==s.amountFen()&&r.currency().equals(s.currency());}
    @Override public void pay(UUID tenant,Request r){allowed(tenant);String fault=behavior(tenant,r.purchaseId());
        tx.executeWithoutResult(status->{initialize(tenant,r);jdbc.update("update mock_payment_authority set pay_calls=pay_calls+1 where tenant_id=? and purchase_order_id=?",tenant,r.purchaseId());Snapshot existing=stored(tenant,r.purchaseId()).orElseThrow();if(!"UNPAID".equals(existing.state()))return;if("PAY_TIMEOUT_BEFORE".equals(fault))return;
            String state="DECLINED".equals(fault)?"FAILED":"PAID";jdbc.update("update mock_payment_authority set state=?,payment_reference=?,updated_at=now() where tenant_id=? and purchase_order_id=? and state='UNPAID'",state,"PAID".equals(state)?"MOCK-PAYMENT-"+UUID.randomUUID():null,tenant,r.purchaseId());});
        if(Set.of("PAY_TIMEOUT_BEFORE","PAY_TIMEOUT_AFTER","QUERY_TIMEOUT").contains(fault))throw new ApiException(503,"PAYMENT_STATUS_UNKNOWN","Mock付款结果未知，只能查单");
    }
    @Override public Snapshot query(UUID tenant,Request r){allowed(tenant);String fault=behavior(tenant,r.purchaseId());if("QUERY_TIMEOUT".equals(fault))throw new ApiException(503,"PAYMENT_QUERY_TIMEOUT","Mock只读查单暂不可用");Snapshot snapshot=stored(tenant,r.purchaseId()).orElseGet(()->purchase(tenant,r));if("AUTHORITY_MISMATCH".equals(fault))return new Snapshot(snapshot.purchaseId(),snapshot.supplierAccountId(),snapshot.externalOrderId(),Math.addExact(snapshot.amountFen(),1),snapshot.currency(),snapshot.state(),snapshot.paymentReference());return snapshot;}
    @Override public Snapshot simulateReceipt(UUID tenant,Request r,String outcome){allowed(tenant);if(!Set.of("UNPAID","PAID","FAILED").contains(outcome))throw M1Support.invalid();return tx.execute(status->{initialize(tenant,r);Snapshot old=stored(tenant,r.purchaseId()).orElseThrow();if("UNPAID".equals(old.state()))jdbc.update("update mock_payment_authority set state=?,payment_reference=?,updated_at=now() where tenant_id=? and purchase_order_id=? and state='UNPAID'",outcome,"PAID".equals(outcome)?"MOCK-PAYMENT-"+UUID.randomUUID():null,tenant,r.purchaseId());return stored(tenant,r.purchaseId()).orElseThrow();});}
}
