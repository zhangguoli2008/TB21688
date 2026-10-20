package com.smartprocure.sales;

import com.smartprocure.common.M1Support;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class SalesOrderService {
    public record Order(UUID id,UUID storeId,String storeName,String platform,String externalOrderId,String sourceStatus,String localStatus,String currency,long paidAmountFen,Instant paidAt,Instant promisedShipAt,Instant sourceUpdatedAt,long sourceVersion,Instant createdAt,long version,String mode) { }
    public record Item(UUID id,String externalItemId,String externalSkuId,String title,int qty,long paidAllocatedFen,int cancelledQty,int fulfilledQty) { }
    public record Timeline(UUID id,String eventId,String action,long sourceVersion,Instant occurredAt) { }
    private final JdbcTemplate jdbc;
    private final M1Support support;
    private final StoreService stores;
    public SalesOrderService(JdbcTemplate jdbc,M1Support support,StoreService stores) { this.jdbc=jdbc;this.support=support;this.stores=stores; }
    private static Instant instant(ResultSet r,String column)throws SQLException { Timestamp value=r.getTimestamp(column);return value==null?null:value.toInstant(); }
    private static Order row(ResultSet r,int n)throws SQLException { return new Order(r.getObject("id",UUID.class),r.getObject("store_id",UUID.class),r.getString("display_name"),r.getString("platform"),r.getString("external_order_id"),r.getString("source_status"),r.getString("local_status"),r.getString("currency"),r.getLong("paid_amount_fen"),instant(r,"paid_at"),instant(r,"promised_ship_at"),instant(r,"source_updated_at"),r.getLong("source_version"),instant(r,"created_at"),r.getLong("version"),"MOCK"); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public M1Support.Page<Order> list(TenantPrincipal p,int limit,int offset,UUID store,String sourceStatus,String q,Instant from,Instant to) {
        M1Support.page(limit,offset);
        if(sourceStatus!=null&&!Set.of("UNPAID","PAID","CANCELLED","REFUNDING","REFUNDED").contains(sourceStatus)||q!=null&&q.length()>80||from!=null&&to!=null&&!from.isBefore(to))throw M1Support.invalid();
        if(store!=null)stores.get(p,store);
        List<Object> args=new ArrayList<>();String where=support.scope(p,"s",args);
        if(store!=null){where+=" and o.store_id=?";args.add(store);}if(sourceStatus!=null){where+=" and o.source_status=?";args.add(sourceStatus);}if(q!=null&&!q.isBlank()){where+=" and o.external_order_id=?";args.add(q);}
        if(from!=null){where+=" and o.source_updated_at>=?";args.add(Timestamp.from(from));}if(to!=null){where+=" and o.source_updated_at<?";args.add(Timestamp.from(to));}
        String tables=" from sales_orders o join stores s on s.tenant_id=o.tenant_id and s.id=o.store_id where "+where;
        long total=jdbc.queryForObject("select count(*)"+tables,Long.class,args.toArray());args.add(limit);args.add(offset);
        return new M1Support.Page<>(jdbc.query("select o.*,s.display_name,s.platform"+tables+" order by o.created_at desc,o.id desc limit ? offset ?",SalesOrderService::row,args.toArray()),total,limit,offset);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(TenantPrincipal p,UUID id) {
        List<Object> args=new ArrayList<>();String scope=support.scope(p,"s",args);args.add(id);
        Order order=jdbc.query("select o.*,s.display_name,s.platform from sales_orders o join stores s on s.tenant_id=o.tenant_id and s.id=o.store_id where "+scope+" and o.id=?",SalesOrderService::row,args.toArray()).stream().findFirst().orElseThrow(M1Support::missing);
        var items=jdbc.query("select * from sales_order_items where tenant_id=? and sales_order_id=? order by external_item_id",(r,n)->new Item(r.getObject("id",UUID.class),r.getString("external_item_id"),r.getString("external_sku_id"),r.getString("title"),r.getInt("qty"),r.getLong("paid_allocated_fen"),r.getInt("cancelled_qty"),r.getInt("fulfilled_qty")),p.currentTenantId(),id);
        var timeline=jdbc.query("select * from sales_order_timeline where tenant_id=? and sales_order_id=? order by occurred_at desc,id desc limit 100",(r,n)->new Timeline(r.getObject("id",UUID.class),r.getString("event_id"),r.getString("action"),r.getLong("source_version"),instant(r,"occurred_at")),p.currentTenantId(),id).reversed();
        Map<String,Object> detail=new LinkedHashMap<>();
        // Flatten the documented detail DTO without exposing persistence fields.
        detail.put("id",order.id());detail.put("storeId",order.storeId());detail.put("storeName",order.storeName());detail.put("platform",order.platform());detail.put("externalOrderId",order.externalOrderId());detail.put("sourceStatus",order.sourceStatus());detail.put("localStatus",order.localStatus());detail.put("currency",order.currency());detail.put("paidAmountFen",order.paidAmountFen());detail.put("paidAt",order.paidAt());detail.put("promisedShipAt",order.promisedShipAt());detail.put("sourceUpdatedAt",order.sourceUpdatedAt());detail.put("sourceVersion",order.sourceVersion());detail.put("createdAt",order.createdAt());detail.put("version",order.version());detail.put("mode","MOCK");detail.put("items",items);detail.put("timeline",timeline);return detail;
    }
}
