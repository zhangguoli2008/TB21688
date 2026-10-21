package com.smartprocure.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.common.ApiException;
import com.smartprocure.common.M1Support;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** Durable synthetic supplier authority. It never contacts a real commerce or payment platform. */
@Component
public class MockSupplierAdapter implements SupplierProviderAdapter {
    private record Offer(UUID account,long unit,long shipping,long fee,int stock,int minimum,String currency,
                         Set<String> regions,Set<String> ciphertext,int ttl,String fault,long version) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RealChannelGuard guard;
    private final TransactionTemplate tx;
    public MockSupplierAdapter(JdbcTemplate jdbc,ObjectMapper mapper,RealChannelGuard guard,org.springframework.transaction.PlatformTransactionManager manager) { this.jdbc=jdbc;this.mapper=mapper;this.guard=guard;this.tx=new TransactionTemplate(manager); }
    @Override public String provider() { return "MOCK_SUPPLIER"; }
    @Override public Set<Capability> capabilities() { return Set.of(Capability.DEMO_PROBE,Capability.MOCK_SUPPLIER_QUOTE,Capability.MOCK_SUPPLIER_CREATE,Capability.MOCK_SUPPLIER_QUERY); }
    private void allowed(UUID tenant) {
        if(!guard.demoEnabled())throw M1Support.missing();guard.checkDatabaseFlags(tenant);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tenants where id=? and status='ACTIVE')",Boolean.class,tenant)))throw M1Support.conflict("TENANT_UNAVAILABLE");
    }
    private Set<String> strings(String json) {
        try { Set<String> result=new HashSet<>();for(var node:mapper.readTree(json))result.add(node.asText());return Set.copyOf(result); }
        catch(java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    private Offer offer(UUID tenant,UUID id,boolean lock) {
        return jdbc.query("select o.*,a.status account_status from supplier_offers o join supplier_accounts a on a.tenant_id=o.tenant_id and a.id=o.supplier_account_id where o.tenant_id=? and o.id=?"+(lock?" for update of o,a":""),(r,n)->{
            if(!"ACTIVE".equals(r.getString("state"))||!"ACTIVE".equals(r.getString("account_status"))||r.getObject("last_unit_price_fen")==null)throw M1Support.conflict("SUPPLIER_UNAVAILABLE");
            return new Offer(r.getObject("supplier_account_id",UUID.class),r.getLong("last_unit_price_fen"),r.getLong("shipping_fen"),r.getLong("fee_fen"),r.getInt("stock_qty"),r.getInt("min_order_qty"),r.getString("currency"),strings(r.getString("regions")),strings(r.getString("ciphertext_platforms")),r.getInt("quote_valid_seconds"),r.getString("mock_behavior"),r.getLong("version"));
        },tenant,id).stream().findFirst().orElseThrow(M1Support::missing);
    }
    @Override public Quote quote(UUID tenant,UUID offerId,int qty) {
        allowed(tenant);Offer o=offer(tenant,offerId,false);
        if("QUOTE_TIMEOUT".equals(o.fault()))throw new ApiException(503,"SUPPLIER_TIMEOUT","Mock报价暂不可用");
        Instant now=Instant.now();long unit="PRICE_RISE".equals(o.fault())?Math.addExact(o.unit(),1000):o.unit();
        return new Quote(offerId,o.account(),unit,o.shipping(),o.fee(),"OUT_OF_STOCK".equals(o.fault())?0:o.stock(),o.minimum(),o.currency(),o.regions(),o.ciphertext(),now,now.plusSeconds(o.ttl()),o.version());
    }
    @Override public Purchase create(UUID tenant,CreateRequest request) {
        allowed(tenant);
        if(request.qty()<1||request.clientReference()==null||!request.clientReference().matches("MOCK-PI-[a-f0-9-]{36}"))throw M1Support.invalid();
        // This transaction represents the separate Mock supplier, not the local procurement transaction.
        final String[] fault={null};
        Purchase result=tx.execute(status->{
            Offer o=offer(tenant,request.offerId(),true);fault[0]=o.fault();
            if(!o.account().equals(request.supplierAccountId()))throw M1Support.conflict("SUPPLIER_ACCOUNT_MISMATCH");
            var existing=authority(tenant,request.supplierAccountId(),request.clientReference());
            if(existing.isPresent())return existing.get();
            if("CREATE_TIMEOUT_BEFORE".equals(o.fault()))throw new ApiException(503,"CREATE_UNKNOWN","Mock创单结果未知，必须查单");
            if(!request.quoteExpiresAt().isAfter(Instant.now()))throw M1Support.conflict("QUOTE_EXPIRED");
            if(o.version()!=request.offerVersion())throw M1Support.conflict("OFFER_CHANGED");
            long unit="PRICE_RISE".equals(o.fault())?Math.addExact(o.unit(),1000):o.unit();
            long total=Math.addExact(Math.addExact(Math.multiplyExact(unit,(long)request.qty()),o.shipping()),o.fee());
            if(total!=request.totalFen()||!o.currency().equals(request.currency()))throw M1Support.conflict("QUOTE_CHANGED");
            if("OUT_OF_STOCK".equals(o.fault())||o.stock()<request.qty()||request.qty()<o.minimum())throw M1Support.conflict("STOCK_INSUFFICIENT");
            String external="MOCK-PURCHASE-"+UUID.randomUUID();
            jdbc.update("insert into mock_purchase_authority(tenant_id,supplier_account_id,client_reference,external_order_id,offer_id,qty,total_fen,currency) values(?,?,?,?,?,?,?,?)",tenant,o.account(),request.clientReference(),external,request.offerId(),request.qty(),total,o.currency());
            jdbc.update("update supplier_offers set stock_qty=stock_qty-? where tenant_id=? and id=?",request.qty(),tenant,request.offerId());
            return new Purchase(external,request.clientReference(),o.account(),total,o.currency(),"AWAITING_PAYMENT");
        });
        if(Set.of("CREATE_TIMEOUT_AFTER","QUERY_TIMEOUT").contains(fault[0]))throw new ApiException(503,"CREATE_UNKNOWN","Mock创单结果未知，必须查单");
        return result;
    }
    private Optional<Purchase> authority(UUID tenant,UUID account,String reference) {
        return jdbc.query("select external_order_id,client_reference,supplier_account_id,total_fen,currency,state from mock_purchase_authority where tenant_id=? and supplier_account_id=? and client_reference=?",(r,n)->new Purchase(r.getString(1),r.getString(2),r.getObject(3,UUID.class),r.getLong(4),r.getString(5),r.getString(6)),tenant,account,reference).stream().findFirst();
    }
    @Override public Optional<Purchase> query(UUID tenant,UUID account,String reference) {
        allowed(tenant);
        var faults=jdbc.query("select o.mock_behavior from mock_purchase_authority p join supplier_offers o on o.tenant_id=p.tenant_id and o.id=p.offer_id where p.tenant_id=? and p.supplier_account_id=? and p.client_reference=?",(r,n)->r.getString(1),tenant,account,reference);
        if(faults.contains("QUERY_TIMEOUT"))throw new ApiException(503,"SUPPLIER_TIMEOUT","Mock查单暂不可用，继续冻结");
        return authority(tenant,account,reference);
    }
}
