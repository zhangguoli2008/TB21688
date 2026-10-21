package com.smartprocure.procurement;

import com.smartprocure.common.ApiException;
import com.smartprocure.store.StoreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Bounded control-plane scan; each business evaluation below is tenant-scoped. */
@Service
public class ProcurementPlanner {
    private final JdbcTemplate jdbc;
    private final ProcurementService service;
    private final StoreService stores;
    private UUID cursor;
    public ProcurementPlanner(JdbcTemplate jdbc,ProcurementService service,StoreService stores) { this.jdbc=jdbc;this.service=service;this.stores=stores; }
    public synchronized void runOnce() {
        stores.requireDemo();
        String scan="""
            select i.tenant_id,i.id from sales_order_items i
            join sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id
            join tenants t on t.id=i.tenant_id
            where t.status='ACTIVE' and o.source_status='PAID' and i.qty>i.cancelled_qty+i.fulfilled_qty
            and exists(select 1 from sku_mappings m where m.tenant_id=i.tenant_id and m.store_id=o.store_id and m.sales_sku_id=i.external_sku_id and m.status='CONFIRMED')
            and not exists(select 1 from procurement_policies p where p.tenant_id=i.tenant_id and (p.store_id is null or p.store_id=o.store_id) and not p.enabled)
            and not exists(select 1 from purchase_intents pi where pi.tenant_id=i.tenant_id and pi.sales_order_item_id=i.id and pi.fulfillment_slice_no=1)
            """+(cursor==null?"":" and i.id>?::uuid")+" order by i.id limit 20";
        var candidates=jdbc.query(scan,(r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)},cursor==null?new Object[]{}:new Object[]{cursor});
        if(candidates.isEmpty()){cursor=null;return;}cursor=candidates.getLast()[1];
        for(var item:candidates) {
            try { service.plan(item[0],item[1]); }
            catch(ApiException expected) { /* No eligible intent is written when authorization or mutable rules change. */ }
        }
    }
}
