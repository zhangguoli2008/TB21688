package com.smartprocure.procurement;

import com.smartprocure.common.ApiException;
import com.smartprocure.common.M1Support;
import com.smartprocure.sourcing.SourcingService;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

@Service
public class ProcurementRuleEngine {
    public record Reason(String code,String message) { }
    public record Candidate(UUID mappingId,UUID offerId,int priority,boolean allowed,List<Reason> reasons,SourcingService.QuoteView quote,Long estimatedProfitFen) { }
    public record Evaluation(UUID salesOrderItemId,UUID storeId,int qty,boolean allowed,List<Reason> reasons,List<Candidate> candidates,UUID mappingId,Long mappingVersion,long salesItemVersion,long salesSourceVersion,SourcingService.QuoteView selectedQuote,ProcurementPolicyService.PolicySnapshot policySnapshot,String mode) { }
    private record Item(UUID storeId,String sku,int originalQty,int qty,long paidFen,String currency,String sourceStatus,long itemVersion,long sourceVersion) { }
    private record Mapping(UUID id,UUID offer,int priority,long version,String compatibleSku) { }
    private final JdbcTemplate jdbc;
    private final StoreService stores;
    private final ProcurementPolicyService policies;
    private final SourcingService sourcing;
    public ProcurementRuleEngine(JdbcTemplate jdbc,StoreService stores,ProcurementPolicyService policies,SourcingService sourcing) { this.jdbc=jdbc;this.stores=stores;this.policies=policies;this.sourcing=sourcing; }
    public Evaluation evaluate(TenantPrincipal p,UUID item) {
        UUID store=jdbc.query("select o.store_id from sales_order_items i join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where i.tenant_id=? and i.id=?",(r,n)->r.getObject(1,UUID.class),p.currentTenantId(),item).stream().findFirst().orElseThrow(M1Support::missing);
        stores.get(p,store);return evaluate(p.currentTenantId(),item);
    }
    public Evaluation evaluate(UUID tenant,UUID itemId) {
        stores.requireDemo();
        Item item=jdbc.query("select i.*,o.store_id,o.currency,o.source_status,o.source_version from sales_order_items i join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where i.tenant_id=? and i.id=?",(r,n)->new Item(r.getObject("store_id",UUID.class),r.getString("external_sku_id"),r.getInt("qty"),r.getInt("qty")-r.getInt("cancelled_qty")-Math.max(r.getInt("fulfilled_qty"),r.getInt("shipment_ack_qty"))-r.getInt("aftersale_hold_qty"),r.getLong("paid_allocated_fen"),r.getString("currency"),r.getString("source_status"),r.getLong("version"),r.getLong("source_version")),tenant,itemId).stream().findFirst().orElseThrow(M1Support::missing);
        var snapshot=policies.getEffective(tenant,item.storeId());var policy=snapshot.effective();List<Reason> reasons=new ArrayList<>();
        try { stores.requireActive(tenant,item.storeId(),false); } catch(ApiException ex) { reasons.add(reason(ex.code())); }
        if(!"PAID".equals(item.sourceStatus()))reasons.add(reason("SALES_ORDER_NOT_PAID"));
        if(item.qty()<=0)reasons.add(reason("NO_REMAINING_QUANTITY"));
        if(Boolean.TRUE.equals(jdbc.queryForObject("select aftersale_hold_qty>0 from sales_order_items where tenant_id=? and id=?",Boolean.class,tenant,itemId)))reasons.add(reason("AFTERSALE_ALLOCATION_REVIEW"));
        if(!policy.enabled())reasons.add(reason("PROCUREMENT_DISABLED"));
        if(!"CNY".equals(item.currency()))reasons.add(reason("CURRENCY_UNSUPPORTED"));
        List<Mapping> mappings=jdbc.query("select m.id,m.supplier_offer_id,m.priority,m.version,f.compatible_sales_sku_id from sku_mappings m join supplier_offers f on f.tenant_id=m.tenant_id and f.id=m.supplier_offer_id where m.tenant_id=? and m.store_id=? and m.sales_sku_id=? and m.status='CONFIRMED' order by m.priority,m.id",(r,n)->new Mapping(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),r.getLong(4),r.getString(5)),tenant,item.storeId(),item.sku());
        if(mappings.isEmpty())reasons.add(reason("MAPPING_NOT_CONFIRMED"));
        List<Candidate> candidates=new ArrayList<>();
        BigInteger tenantUsed=dailyUsed(tenant,null,itemId),storeUsed=dailyUsed(tenant,item.storeId(),itemId);
        for(Mapping mapping:mappings) {
            List<Reason> excluded=new ArrayList<>(reasons);SourcingService.QuoteView quote=null;Long profit=null;
            if(!item.sku().equals(mapping.compatibleSku()))excluded.add(reason("SPECIFICATION_MISMATCH"));
            if(excluded.isEmpty()) {
                try {
                    quote=sourcing.persistedQuote(tenant,item.storeId(),mapping.offer(),item.qty());
                    if(quote.stock()<item.qty())excluded.add(reason("STOCK_INSUFFICIENT"));
                    if(item.qty()<quote.minimumQty())excluded.add(reason("MINIMUM_QUANTITY_NOT_MET"));
                    if(!quote.cipherSupported())excluded.add(reason("CIPHERTEXT_UNSUPPORTED"));
                    if(!quote.regionSupported())excluded.add(reason("REGION_UNSUPPORTED"));
                    if(!quote.expiresAt().isAfter(Instant.now()))excluded.add(reason("QUOTE_EXPIRED"));
                    if(!quote.currency().equals(item.currency()))excluded.add(reason("CURRENCY_UNSUPPORTED"));
                    if(quote.shippingFen()>policy.maxShippingFen())excluded.add(reason("SHIPPING_LIMIT_EXCEEDED"));
                    if(quote.totalFen()>policy.maxSingleFen())excluded.add(reason("SINGLE_LIMIT_EXCEEDED"));
                    BigInteger total=BigInteger.valueOf(quote.totalFen());
                    if(tenantUsed.add(total).compareTo(BigInteger.valueOf(snapshot.tenantPolicy().maxDailyFen()))>0)excluded.add(reason("TENANT_DAILY_LIMIT_EXCEEDED"));
                    if(storeUsed.add(total).compareTo(BigInteger.valueOf(snapshot.storePolicy().maxDailyFen()))>0)excluded.add(reason("STORE_DAILY_LIMIT_EXCEEDED"));
                    long remainingPaid=BigInteger.valueOf(item.paidFen()).multiply(BigInteger.valueOf(item.qty())).divide(BigInteger.valueOf(item.originalQty())).longValueExact();
                    profit=Math.subtractExact(Math.subtractExact(Math.subtractExact(remainingPaid,quote.totalFen()),policy.platformFeeFen()),policy.reserveFen());
                    if(profit<policy.minProfitFen())excluded.add(reason("PROFIT_BELOW_MINIMUM"));
                } catch(ArithmeticException ex) { excluded.add(reason("AMOUNT_OVERFLOW")); }
                catch(ApiException ex) {
                    if(Set.of("SUPPLIER_TIMEOUT","CAPABILITY_UNAVAILABLE","AMOUNT_OVERFLOW","SUPPLIER_UNAVAILABLE","OFFER_UNAVAILABLE","AUTHORIZATION_UNAVAILABLE","STORE_UNAVAILABLE","STOCK_INSUFFICIENT").contains(ex.code()))excluded.add(reason(ex.code()));else throw ex;
                }
            }
            candidates.add(new Candidate(mapping.id(),mapping.offer(),mapping.priority(),excluded.isEmpty(),List.copyOf(excluded),quote,profit));
        }
        candidates.sort(Comparator.comparing((Candidate c)->!c.allowed()).thenComparingLong(c->c.quote()==null?Long.MAX_VALUE:c.quote().totalFen()).thenComparingInt(Candidate::priority).thenComparing(c->c.mappingId().toString()));
        Candidate selected=candidates.stream().filter(Candidate::allowed).findFirst().orElse(null);
        if(selected==null&&reasons.isEmpty())reasons.add(reason("NO_ELIGIBLE_SUPPLIER"));
        Long mappingVersion=selected==null?null:mappings.stream().filter(m->m.id().equals(selected.mappingId())).findFirst().orElseThrow().version();
        return new Evaluation(itemId,item.storeId(),item.qty(),selected!=null,List.copyOf(reasons),List.copyOf(candidates),selected==null?null:selected.mappingId(),mappingVersion,item.itemVersion(),item.sourceVersion(),selected==null?null:selected.quote(),snapshot,"MOCK");
    }
    private BigInteger dailyUsed(UUID tenant,UUID store,UUID item) {
        String filter=store==null?"":" and o.store_id=?";List<Object> args=new ArrayList<>(List.of(tenant,item));if(store!=null)args.add(store);
        String amount=jdbc.queryForObject("select coalesce(sum(p.estimated_total_fen),0)::text from purchase_intents p join sales_order_items i on i.tenant_id=p.tenant_id and i.id=p.sales_order_item_id join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where p.tenant_id=? and p.sales_order_item_id<>? and p.state not in ('FAILED','BLOCKED') and p.created_at >= date_trunc('day',now() at time zone 'UTC') at time zone 'UTC' and p.created_at < (date_trunc('day',now() at time zone 'UTC')+interval '1 day') at time zone 'UTC'"+filter,String.class,args.toArray());
        return new BigInteger(amount);
    }
    public static Reason reason(String code) { return new Reason(code,switch(code) {
        case "MAPPING_NOT_CONFIRMED" -> "销售SKU没有已人工确认的货源映射";
        case "SPECIFICATION_MISMATCH" -> "销售SKU与合成货源规格不一致";
        case "SALES_ORDER_NOT_PAID" -> "销售订单未付款、已取消或退款中";
        case "AFTERSALE_ALLOCATION_REVIEW" -> "该子项存在售后冻结，剩余数量需人工核对分配";
        case "NO_REMAINING_QUANTITY" -> "没有剩余应履约数量";
        case "PROCUREMENT_DISABLED" -> "租户或店铺采购策略已暂停";
        case "STOCK_INSUFFICIENT" -> "供应商库存不足";
        case "MINIMUM_QUANTITY_NOT_MET" -> "未达到供应商最小起订量";
        case "CIPHERTEXT_UNSUPPORTED" -> "货源不支持订单来源平台的密文履约";
        case "REGION_UNSUPPORTED" -> "货源不支持合成履约地区";
        case "QUOTE_EXPIRED" -> "报价已过期，需要重新报价";
        case "SUPPLIER_TIMEOUT" -> "Mock供应商报价超时，转人工审核";
        case "CURRENCY_UNSUPPORTED" -> "币种不一致或不受支持";
        case "SHIPPING_LIMIT_EXCEEDED" -> "运费超过采购策略上限";
        case "SINGLE_LIMIT_EXCEEDED" -> "采购金额超过单笔上限";
        case "TENANT_DAILY_LIMIT_EXCEEDED" -> "租户当日采购额度不足";
        case "STORE_DAILY_LIMIT_EXCEEDED" -> "店铺当日采购额度不足";
        case "PROFIT_BELOW_MINIMUM" -> "预计利润低于采购策略下限";
        case "AMOUNT_OVERFLOW" -> "金额超出可安全计算范围";
        case "NO_ELIGIBLE_SUPPLIER" -> "所有已确认货源均被规则阻断";
        default -> "供应商或授权能力不可用，转人工审核";
    }); }
}
