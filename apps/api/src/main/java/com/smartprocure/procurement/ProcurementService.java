package com.smartprocure.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.audit.AuditService;
import com.smartprocure.common.M1Support;
import com.smartprocure.common.M4Activity;
import com.smartprocure.sourcing.SourcingService;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class ProcurementService {
    public record Create(@NotNull UUID salesOrderItemId,@NotNull UUID quoteId) { }
    public record Evaluate(@NotNull UUID salesOrderItemId) { }
    public record Intent(UUID id,UUID storeId,UUID salesOrderId,UUID salesOrderItemId,UUID mappingId,int qty,
                         String state,long estimatedTotalFen,String currency,SourcingService.QuoteView quote,
                         List<ProcurementRuleEngine.Reason> reasons,String failureCode,UUID purchaseOrderId,
                         Instant createdAt,Instant updatedAt,long version,String mode) { }
    public record Purchase(UUID id,UUID purchaseIntentId,UUID storeId,UUID salesOrderItemId,UUID supplierAccountId,
                           String externalOrderId,String state,long totalFen,String currency,Instant createdAt,
                           Instant updatedAt,long version,String mode) { }
    private final M4Activity activity;
    private static final Set<String> STATES=Set.of("APPROVED","CREATING","CREATE_UNKNOWN","AWAITING_PAYMENT","REVIEW_REQUIRED");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final M1Support support;
    private final StoreService stores;
    private final SourcingService sourcing;
    private final ProcurementRuleEngine engine;
    private final ProcurementPolicyService policies;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public ProcurementService(JdbcTemplate jdbc,ObjectMapper mapper,M1Support support,StoreService stores,SourcingService sourcing,ProcurementRuleEngine engine,ProcurementPolicyService policies,AuditService audit,org.springframework.transaction.PlatformTransactionManager manager,M4Activity activity) { this.jdbc=jdbc;this.mapper=mapper;this.support=support;this.stores=stores;this.sourcing=sourcing;this.engine=engine;this.policies=policies;this.audit=audit;this.tx=new TransactionTemplate(manager); this.activity=activity; }
    private <T> T parse(String json,Class<T> type) { if(json==null)return null;try{return mapper.readValue(json,type);}catch(java.io.IOException ex){throw new IllegalStateException(ex);} }
    private Intent intentRow(ResultSet r,int n)throws SQLException {
        String failure=r.getString("failure_code");
        return new Intent(r.getObject("id",UUID.class),r.getObject("store_id",UUID.class),r.getObject("sales_order_id",UUID.class),r.getObject("sales_order_item_id",UUID.class),r.getObject("mapping_id",UUID.class),r.getInt("qty"),r.getString("state"),r.getLong("estimated_total_fen"),r.getString("currency"),parse(r.getString("quote_snapshot"),SourcingService.QuoteView.class),failure==null?List.of():List.of(new ProcurementRuleEngine.Reason(failure,"采购已暂停，需人工核对")),failure,r.getObject("purchase_order_id",UUID.class),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getLong("version"),"MOCK");
    }
    private Purchase purchaseRow(ResultSet r,int n)throws SQLException { return new Purchase(r.getObject("id",UUID.class),r.getObject("purchase_intent_id",UUID.class),r.getObject("store_id",UUID.class),r.getObject("sales_order_item_id",UUID.class),r.getObject("supplier_account_id",UUID.class),r.getString("external_order_id"),r.getString("state"),r.getLong("total_fen"),r.getString("currency"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getLong("version"),"MOCK"); }
    private static final String INTENTS=" from purchase_intents i join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id join sales_orders so on so.tenant_id=si.tenant_id and so.id=si.sales_order_id join stores s on s.tenant_id=so.tenant_id and s.id=so.store_id left join purchase_orders p on p.tenant_id=i.tenant_id and p.purchase_intent_id=i.id where ";
    private static final String INTENT_SELECT="select i.*,so.store_id,so.id sales_order_id,p.id purchase_order_id";
    private static final String PURCHASES=" from purchase_orders p join purchase_intents i on i.tenant_id=p.tenant_id and i.id=p.purchase_intent_id join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id join sales_orders so on so.tenant_id=si.tenant_id and so.id=si.sales_order_id join stores s on s.tenant_id=so.tenant_id and s.id=so.store_id where ";
    private static final String PURCHASE_SELECT="select p.*,so.store_id,i.sales_order_item_id";
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public M1Support.Page<Intent> intents(TenantPrincipal p,int limit,int offset,UUID store,String state) {
        stores.requireDemo();M1Support.page(limit,offset);List<Object> args=new ArrayList<>();String where=filters(p,store,state,"i",args);
        long total=jdbc.queryForObject("select count(*)"+INTENTS+where,Long.class,args.toArray());args.add(limit);args.add(offset);
        return new M1Support.Page<>(jdbc.query(INTENT_SELECT+INTENTS+where+" order by i.created_at desc,i.id desc limit ? offset ?",this::intentRow,args.toArray()),total,limit,offset);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public M1Support.Page<Purchase> purchases(TenantPrincipal p,int limit,int offset,UUID store,String state) {
        stores.requireDemo();M1Support.page(limit,offset);if("APPROVED".equals(state))throw M1Support.invalid();List<Object> args=new ArrayList<>();String where=filters(p,store,state,"p",args);
        long total=jdbc.queryForObject("select count(*)"+PURCHASES+where,Long.class,args.toArray());args.add(limit);args.add(offset);
        return new M1Support.Page<>(jdbc.query(PURCHASE_SELECT+PURCHASES+where+" order by p.created_at desc,p.id desc limit ? offset ?",this::purchaseRow,args.toArray()),total,limit,offset);
    }
    private String filters(TenantPrincipal p,UUID store,String state,String alias,List<Object> args) {
        if(store!=null)stores.get(p,store);if(state!=null&&!STATES.contains(state))throw M1Support.invalid();
        String where=support.scope(p,"s",args);if(store!=null){where+=" and s.id=?";args.add(store);}if(state!=null){where+=" and "+alias+".state=?";args.add(state);}return where;
    }
    public Intent intent(TenantPrincipal p,UUID id) { stores.requireDemo();List<Object> args=new ArrayList<>();String where=support.scope(p,"s",args);args.add(id);return jdbc.query(INTENT_SELECT+INTENTS+where+" and i.id=?",this::intentRow,args.toArray()).stream().findFirst().orElseThrow(M1Support::missing); }
    public Purchase purchase(TenantPrincipal p,UUID id) { stores.requireDemo();List<Object> args=new ArrayList<>();String where=support.scope(p,"s",args);args.add(id);return jdbc.query(PURCHASE_SELECT+PURCHASES+where+" and p.id=?",this::purchaseRow,args.toArray()).stream().findFirst().orElseThrow(M1Support::missing); }
    private Intent internal(UUID tenant,UUID id) { return jdbc.query(INTENT_SELECT+INTENTS+"i.tenant_id=? and i.id=?",this::intentRow,tenant,id).stream().findFirst().orElseThrow(M1Support::missing); }
    private Optional<UUID> existing(UUID tenant,UUID item) { return jdbc.query("select id from purchase_intents where tenant_id=? and sales_order_item_id=? and fulfillment_slice_no=1",(r,n)->r.getObject(1,UUID.class),tenant,item).stream().findFirst(); }
    public Intent create(TenantPrincipal p,String key,Create input) {
        stores.requireDemo();if(key==null||!key.matches("[A-Za-z0-9_-]{8,160}"))throw M1Support.invalid();
        var replay=jdbc.query("select operation,request_digest,response::text from m1_command_keys where tenant_id=? and command_key=?",(r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)},p.currentTenantId(),key);
        if(!replay.isEmpty()) {
            String[] saved=replay.getFirst();if(!"M2_INTENT_CREATE".equals(saved[0])||!M1Support.digest(support.json(input)).equals(saved[1]))throw M1Support.conflict("IDEMPOTENCY_CONFLICT");
            if(saved[2]!=null){Intent original=parse(saved[2],Intent.class);intent(p,original.id());return original;}
        }
        // Existing unique business intent is returned even if an earlier quote has since expired.
        var found=existing(p.currentTenantId(),input.salesOrderItemId());
        if(found.isPresent()) { Intent current=intent(p,found.get());return tx.execute(status->support.command(p.currentTenantId(),key,"M2_INTENT_CREATE",input,Intent.class,()->current)); }
        var evaluation=engine.evaluate(p,input.salesOrderItemId());
        if(!evaluation.allowed())throw M1Support.conflict("PROCUREMENT_BLOCKED");
        var quote=sourcing.getQuote(p.currentTenantId(),input.quoteId());
        if(!sameQuote(quote,evaluation.selectedQuote())||!quote.expiresAt().isAfter(Instant.now()))throw M1Support.conflict("QUOTE_CHANGED");
        return tx.execute(status->support.command(p.currentTenantId(),key,"M2_INTENT_CREATE",input,Intent.class,()->approve(p.currentTenantId(),p.userId(),evaluation,quote)));
    }
    public void plan(UUID tenant,UUID item) {
        if(existing(tenant,item).isPresent())return;
        var evaluation=engine.evaluate(tenant,item);if(!evaluation.allowed())return;
        tx.executeWithoutResult(status->approve(tenant,null,evaluation,evaluation.selectedQuote()));
    }
    private Intent approve(UUID tenant,UUID actor,ProcurementRuleEngine.Evaluation evaluation,SourcingService.QuoteView quote) {
        var policy=policies.lock(tenant,evaluation.storeId());stores.requireActive(tenant,evaluation.storeId(),true);
        var found=existing(tenant,evaluation.salesOrderItemId());if(found.isPresent())return internal(tenant,found.get());
        validateLocked(tenant,evaluation,policy,quote,true);
        UUID id=UUID.randomUUID();String reference="MOCK-PI-"+id;
        jdbc.update("insert into purchase_intents(id,tenant_id,sales_order_item_id,mapping_id,qty,state,estimated_total_fen,quote_snapshot,policy_snapshot,evaluation_snapshot,quote_expires_at,quote_id,mapping_version,sales_item_version,sales_source_version,supplier_offer_id,supplier_account_id,client_reference,currency) values(?,?,?,?,?,'APPROVED',?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?,?)",id,tenant,evaluation.salesOrderItemId(),evaluation.mappingId(),evaluation.qty(),quote.totalFen(),support.json(quote),support.json(evaluation.policySnapshot()),support.json(evaluation),Timestamp.from(quote.expiresAt()),quote.id(),evaluation.mappingVersion(),evaluation.salesItemVersion(),evaluation.salesSourceVersion(),quote.offerId(),quote.supplierAccountId(),reference,quote.currency());
        UUID order=jdbc.queryForObject("select sales_order_id from sales_order_items where tenant_id=? and id=?",UUID.class,tenant,evaluation.salesOrderItemId());
        activity.record(tenant,order,null,"PROCUREMENT","MOCK_PURCHASE_INTENT_CREATED","PURCHASE_INTENT",id,null,"intent:"+id,false);
        jdbc.update("insert into outbox_events(event_type,tenant_id,aggregate_id,payload) values('M2_PURCHASE_CREATE',?,?,'{}'::jsonb)",tenant,id);
        audit.object(tenant,actor,"MOCK_PURCHASE_INTENT_APPROVED","PURCHASE_INTENT",id);return internal(tenant,id);
    }
    /** Recheck every mutable local prerequisite while tenant/store policy reservation locks are held. */
    public void validateLocked(UUID tenant,ProcurementRuleEngine.Evaluation evaluation,ProcurementPolicyService.PolicySnapshot policy,SourcingService.QuoteView quote,boolean reserve) {
        if(!policy.effective().enabled()||policy.tenantVersion()!=evaluation.policySnapshot().tenantVersion()||policy.storeVersion()!=evaluation.policySnapshot().storeVersion())throw M1Support.conflict("POLICY_CHANGED");
        var item=jdbc.query("select i.version,o.source_version,o.source_status,i.qty-i.cancelled_qty-greatest(i.fulfilled_qty,i.shipment_ack_qty)-i.aftersale_hold_qty qty from sales_order_items i join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where i.tenant_id=? and i.id=? for update of o,i",(r,n)->new Object[]{r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4)},tenant,evaluation.salesOrderItemId()).stream().findFirst().orElseThrow(M1Support::missing);
        if((long)item[0]!=evaluation.salesItemVersion()||(long)item[1]!=evaluation.salesSourceVersion()||!"PAID".equals(item[2])||(int)item[3]!=evaluation.qty())throw M1Support.conflict("SALES_CHANGED");
        var mapping=jdbc.query("select version,status,supplier_offer_id from sku_mappings where tenant_id=? and id=? for update",(r,n)->new Object[]{r.getLong(1),r.getString(2),r.getObject(3,UUID.class)},tenant,evaluation.mappingId()).stream().findFirst().orElseThrow(M1Support::missing);
        if((long)mapping[0]!=evaluation.mappingVersion()||!"CONFIRMED".equals(mapping[1])||!quote.offerId().equals(mapping[2]))throw M1Support.conflict("MAPPING_CHANGED");
        var offers=jdbc.query("select o.version,o.state,o.stock_qty,a.status from supplier_offers o join supplier_accounts a on a.tenant_id=o.tenant_id and a.id=o.supplier_account_id where o.tenant_id=? and o.id=? for update of o,a",(r,n)->new Object[]{r.getLong(1),r.getString(2),r.getInt(3),r.getString(4)},tenant,quote.offerId());
        if(offers.isEmpty())throw M1Support.conflict("SUPPLIER_UNAVAILABLE");var offer=offers.getFirst();
        if((long)offer[0]!=quote.offerVersion()||!"ACTIVE".equals(offer[1])||!"ACTIVE".equals(offer[3])||(int)offer[2]<quote.qty())throw M1Support.conflict("QUOTE_CHANGED");
        if(!quote.expiresAt().isAfter(Instant.now()))throw M1Support.conflict("QUOTE_EXPIRED");
        if(reserve) {
            java.math.BigInteger day=jdbc.queryForObject("select coalesce(sum(estimated_total_fen),0)::text from purchase_intents where tenant_id=? and created_at>=date_trunc('day',now() at time zone 'UTC') at time zone 'UTC' and state not in ('FAILED','BLOCKED')",(r,n)->new java.math.BigInteger(r.getString(1)),tenant);
            java.math.BigInteger storeDay=jdbc.queryForObject("select coalesce(sum(i.estimated_total_fen),0)::text from purchase_intents i join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id join sales_orders so on so.tenant_id=si.tenant_id and so.id=si.sales_order_id where i.tenant_id=? and so.store_id=? and i.created_at>=date_trunc('day',now() at time zone 'UTC') at time zone 'UTC' and i.state not in ('FAILED','BLOCKED')",(r,n)->new java.math.BigInteger(r.getString(1)),tenant,evaluation.storeId());
            var amount=java.math.BigInteger.valueOf(quote.totalFen());
            if(day.add(amount).compareTo(java.math.BigInteger.valueOf(policy.tenantPolicy().maxDailyFen()))>0||storeDay.add(amount).compareTo(java.math.BigInteger.valueOf(policy.storePolicy().maxDailyFen()))>0)throw M1Support.conflict("DAILY_LIMIT_EXCEEDED");
        }
    }
    public static boolean sameQuote(SourcingService.QuoteView a,SourcingService.QuoteView b) { return a!=null&&b!=null&&a.offerId().equals(b.offerId())&&a.supplierAccountId().equals(b.supplierAccountId())&&a.storeId().equals(b.storeId())&&a.qty()==b.qty()&&a.unitPriceFen()==b.unitPriceFen()&&a.shippingFen()==b.shippingFen()&&a.feeFen()==b.feeFen()&&a.totalFen()==b.totalFen()&&a.currency().equals(b.currency())&&a.offerVersion()==b.offerVersion()&&a.cipherSupported()==b.cipherSupported()&&a.regionSupported()==b.regionSupported(); }
}
