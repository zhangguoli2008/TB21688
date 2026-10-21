package com.smartprocure.sourcing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.audit.AuditService;
import com.smartprocure.common.M1Support;
import com.smartprocure.procurement.ProcurementPolicyService;
import com.smartprocure.provider.SupplierProviderAdapter;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.validation.constraints.*;
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
public class SourcingService {
    public enum Fault { NORMAL,OUT_OF_STOCK,PRICE_RISE,QUOTE_TIMEOUT,CREATE_TIMEOUT_BEFORE,CREATE_TIMEOUT_AFTER,QUERY_TIMEOUT }
    public enum MappingStatus { REVIEW,CONFIRMED,PAUSED }
    public enum CipherPlatform { DOUYIN,TAOBAO }
    public enum Region { CN }
    public record Offer(UUID id,UUID supplierAccountId,String displayName,String externalSkuId,String compatibleSalesSkuId,String specification,long unitPriceFen,long shippingFen,int stock,int minimumQty,Set<String> cipherPlatforms,Set<String> regions,int quoteTtlSeconds,String fault,long version,String mode) { }
    public record OfferConfig(@NotNull @Min(0) @Max(9007199254740991L) Long version,@Min(0) @Max(1000000000L) Long unitPriceFen,@Min(0) @Max(1000000000L) Long shippingFen,@Min(0) @Max(1000000) Integer stock,@Min(1) @Max(1000000) Integer minimumQty,Set<CipherPlatform> cipherPlatforms,Set<Region> regions,@Min(1) @Max(3600) Integer quoteTtlSeconds,Fault fault) { }
    public record QuoteRequest(@NotNull UUID storeId,@NotNull @Min(1) @Max(1000000) Integer qty) { }
    public record QuoteView(UUID id,UUID offerId,UUID supplierAccountId,UUID storeId,int qty,long unitPriceFen,long shippingFen,long feeFen,long totalFen,int stock,int minimumQty,boolean cipherSupported,boolean regionSupported,String currency,Instant quotedAt,Instant expiresAt,long offerVersion,String mode) { }
    public record Mapping(UUID id,UUID storeId,String salesSkuId,UUID supplierOfferId,String supplierName,String specification,int priority,String status,UUID confirmedBy,Instant confirmedAt,long version,String mode) { }
    public record MappingCreate(@NotNull UUID storeId,@NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9_-]+") String salesSkuId,@NotNull UUID supplierOfferId,@Min(0) @Max(10000) Integer priority) { }
    public record MappingUpdate(@NotNull @Min(0) @Max(9007199254740991L) Long version,@NotNull MappingStatus status,@Min(0) @Max(10000) Integer priority,Boolean reviewAcknowledged) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final M1Support support;
    private final StoreService stores;
    private final ProcurementPolicyService policies;
    private final SupplierProviderAdapter supplier;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public SourcingService(JdbcTemplate jdbc,ObjectMapper mapper,M1Support support,StoreService stores,ProcurementPolicyService policies,SupplierProviderAdapter supplier,AuditService audit,org.springframework.transaction.PlatformTransactionManager manager) { this.jdbc=jdbc;this.mapper=mapper;this.support=support;this.stores=stores;this.policies=policies;this.supplier=supplier;this.audit=audit;this.tx=new TransactionTemplate(manager); }
    private Set<String> strings(String json) { try { return new LinkedHashSet<>(mapper.readValue(json,mapper.getTypeFactory().constructCollectionType(List.class,String.class))); } catch(java.io.IOException ex) { throw new IllegalStateException(ex); } }
    private Offer offerRow(ResultSet r,int n)throws SQLException { return new Offer(r.getObject("id",UUID.class),r.getObject("supplier_account_id",UUID.class),r.getString("display_name"),r.getString("external_sku_id"),r.getString("compatible_sales_sku_id"),r.getString("specification"),r.getLong("last_unit_price_fen"),r.getLong("shipping_fen"),r.getInt("stock_qty"),r.getInt("min_order_qty"),strings(r.getString("ciphertext_platforms")),strings(r.getString("regions")),r.getInt("quote_valid_seconds"),r.getString("mock_behavior"),r.getLong("version"),"MOCK"); }
    public Offer offer(UUID tenant,UUID id) { return jdbc.query("select * from supplier_offers where tenant_id=? and id=?",this::offerRow,tenant,id).stream().findFirst().orElseThrow(M1Support::missing); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public M1Support.Page<Offer> offers(TenantPrincipal p,int limit,int offset) {
        stores.requireDemo();M1Support.page(limit,offset);
        long count=jdbc.queryForObject("select count(*) from supplier_offers where tenant_id=?",Long.class,p.currentTenantId());
        return new M1Support.Page<>(jdbc.query("select * from supplier_offers where tenant_id=? order by id limit ? offset ?",this::offerRow,p.currentTenantId(),limit,offset),count,limit,offset);
    }
    @Transactional public Offer configure(TenantPrincipal p,UUID id,OfferConfig input) {
        stores.requireDemo();Offer before=offer(p.currentTenantId(),id);
        if(input.cipherPlatforms()!=null&&input.cipherPlatforms().contains(null)||input.regions()!=null&&input.regions().contains(null))throw M1Support.invalid();
        int changed=jdbc.update("update supplier_offers set last_unit_price_fen=?,shipping_fen=?,stock_qty=?,min_order_qty=?,ciphertext_platforms=?::jsonb,regions=?::jsonb,quote_valid_seconds=?,mock_behavior=?,version=version+1 where tenant_id=? and id=? and version=?",input.unitPriceFen()==null?before.unitPriceFen():input.unitPriceFen(),input.shippingFen()==null?before.shippingFen():input.shippingFen(),input.stock()==null?before.stock():input.stock(),input.minimumQty()==null?before.minimumQty():input.minimumQty(),support.json(input.cipherPlatforms()==null?before.cipherPlatforms():input.cipherPlatforms()),support.json(input.regions()==null?before.regions():input.regions()),input.quoteTtlSeconds()==null?before.quoteTtlSeconds():input.quoteTtlSeconds(),input.fault()==null?before.fault():input.fault().name(),p.currentTenantId(),id,input.version());
        if(changed!=1)throw M1Support.conflict("VERSION_CONFLICT");audit.object(p.currentTenantId(),p.userId(),"MOCK_SUPPLIER_CONFIG_UPDATED","SUPPLIER_OFFER",id);return offer(p.currentTenantId(),id);
    }
    public QuoteView quote(TenantPrincipal p,UUID offer,QuoteRequest input) { stores.get(p,input.storeId());return persistedQuote(p.currentTenantId(),input.storeId(),offer,input.qty(),p.userId()); }
    public QuoteView persistedQuote(UUID tenant,UUID store,UUID offer,int qty) { return persistedQuote(tenant,store,offer,qty,null); }
    private QuoteView persistedQuote(UUID tenant,UUID store,UUID offer,int qty,UUID actor) {
        if(qty<1||qty>1000000)throw M1Support.invalid();
        StoreService.Store source=stores.requireActive(tenant,store,false);var policy=policies.getEffective(tenant,store);
        var q=supplier.quote(tenant,offer,qty);
        long total;try { total=Math.addExact(Math.addExact(Math.multiplyExact(q.unitPriceFen(),qty),q.shippingFen()),q.feeFen()); } catch(ArithmeticException ex) { throw M1Support.conflict("AMOUNT_OVERFLOW"); }
        if(total>9007199254740991L)throw M1Support.conflict("AMOUNT_OVERFLOW");
        QuoteView result=new QuoteView(UUID.randomUUID(),offer,q.supplierAccountId(),store,qty,q.unitPriceFen(),q.shippingFen(),q.feeFen(),total,q.stockQty(),q.minOrderQty(),q.ciphertextPlatforms().contains(source.platform()),q.regions().contains(policy.effective().region()),q.currency(),q.quotedAt(),q.expiresAt(),q.offerVersion(),"MOCK");
        return tx.execute(status->{
        jdbc.update("insert into supplier_quotes(id,tenant_id,store_id,supplier_offer_id,supplier_account_id,qty,total_fen,currency,offer_version,expires_at,snapshot) values(?,?,?,?,?,?,?,?,?,?,?::jsonb)",result.id(),tenant,store,offer,q.supplierAccountId(),qty,total,q.currency(),q.offerVersion(),Timestamp.from(q.expiresAt()),support.json(result));
        audit.object(tenant,actor,"MOCK_SUPPLIER_QUOTED","SUPPLIER_QUOTE",result.id());return result;
        });
    }
    public QuoteView getQuote(UUID tenant,UUID id) { return jdbc.query("select snapshot::text from supplier_quotes where tenant_id=? and id=?",(r,n)->{try {return mapper.readValue(r.getString(1),QuoteView.class);}catch(java.io.IOException ex){throw new IllegalStateException(ex);}},tenant,id).stream().findFirst().orElseThrow(M1Support::missing); }
    private static Mapping mappingRow(ResultSet r,int n)throws SQLException { Timestamp confirmed=r.getTimestamp("confirmed_at");return new Mapping(r.getObject("id",UUID.class),r.getObject("store_id",UUID.class),r.getString("sales_sku_id"),r.getObject("supplier_offer_id",UUID.class),r.getString("display_name"),r.getString("specification"),r.getInt("priority"),r.getString("status"),r.getObject("confirmed_by",UUID.class),confirmed==null?null:confirmed.toInstant(),r.getLong("version"),"MOCK"); }
    private String mappingTables() { return " from sku_mappings m join stores s on s.tenant_id=m.tenant_id and s.id=m.store_id join supplier_offers f on f.tenant_id=m.tenant_id and f.id=m.supplier_offer_id where "; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public M1Support.Page<Mapping> mappings(TenantPrincipal p,int limit,int offset,UUID store,String salesSku,MappingStatus status) {
        stores.requireDemo();M1Support.page(limit,offset);if(store!=null)stores.get(p,store);if(salesSku!=null&&(salesSku.length()>128||!salesSku.matches("[A-Za-z0-9_-]+")))throw M1Support.invalid();
        List<Object> args=new ArrayList<>();String where=support.scope(p,"s",args);if(store!=null){where+=" and m.store_id=?";args.add(store);}if(salesSku!=null){where+=" and m.sales_sku_id=?";args.add(salesSku);}
        if(status!=null){where+=" and m.status=?";args.add(status.name());}
        long count=jdbc.queryForObject("select count(*)"+mappingTables()+where,Long.class,args.toArray());args.add(limit);args.add(offset);
        return new M1Support.Page<>(jdbc.query("select m.*,f.display_name,f.specification"+mappingTables()+where+" order by m.created_at desc,m.id desc limit ? offset ?",SourcingService::mappingRow,args.toArray()),count,limit,offset);
    }
    public Mapping mapping(TenantPrincipal p,UUID id) {
        List<Object> args=new ArrayList<>();String where=support.scope(p,"s",args);args.add(id);
        return jdbc.query("select m.*,f.display_name,f.specification"+mappingTables()+where+" and m.id=?",SourcingService::mappingRow,args.toArray()).stream().findFirst().orElseThrow(M1Support::missing);
    }
    @Transactional public Mapping createMapping(TenantPrincipal p,String key,MappingCreate input) {
        stores.requireDemo();stores.get(p,input.storeId());offer(p.currentTenantId(),input.supplierOfferId());
        return support.command(p.currentTenantId(),key,"M2_MAPPING_CREATE",input,Mapping.class,()->{
            UUID id=UUID.randomUUID();int inserted=jdbc.update("insert into sku_mappings(id,tenant_id,store_id,sales_sku_id,supplier_offer_id,priority) values(?,?,?,?,?,?) on conflict(tenant_id,store_id,sales_sku_id,supplier_offer_id) do nothing",id,p.currentTenantId(),input.storeId(),input.salesSkuId(),input.supplierOfferId(),input.priority()==null?100:input.priority());
            if(inserted!=1)throw M1Support.conflict("MAPPING_EXISTS");audit.object(p.currentTenantId(),p.userId(),"SKU_MAPPING_CREATED","SKU_MAPPING",id);return mapping(p,id);
        });
    }
    @Transactional public Mapping updateMapping(TenantPrincipal p,UUID id,MappingUpdate input) {
        stores.requireDemo();Mapping before=mapping(p,id);boolean confirm=input.status()==MappingStatus.CONFIRMED;
        if(confirm&&!Boolean.TRUE.equals(input.reviewAcknowledged()))throw M1Support.conflict("REVIEW_ACKNOWLEDGEMENT_REQUIRED");
        if(confirm&&!before.salesSkuId().equals(offer(p.currentTenantId(),before.supplierOfferId()).compatibleSalesSkuId()))throw M1Support.conflict("SPECIFICATION_MISMATCH");
        int changed=jdbc.update("update sku_mappings set status=?,priority=?,confirmed_by=case when ? then ?::uuid else confirmed_by end,confirmed_at=case when ? then now() else confirmed_at end,review_acknowledged=case when ? then true else review_acknowledged end,version=version+1,updated_at=now() where tenant_id=? and id=? and version=?",input.status().name(),input.priority()==null?before.priority():input.priority(),confirm,p.userId(),confirm,confirm,p.currentTenantId(),id,input.version());
        if(changed!=1)throw M1Support.conflict("VERSION_CONFLICT");audit.object(p.currentTenantId(),p.userId(),"SKU_MAPPING_UPDATED","SKU_MAPPING",id);return mapping(p,id);
    }
}
