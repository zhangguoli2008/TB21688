package com.smartprocure.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.common.ApiException;
import com.smartprocure.store.StoreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

@Component
public class MockOrderAuthorityAdapter implements SalesOrderProviderAdapter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StoreService stores;
    public MockOrderAuthorityAdapter(JdbcTemplate jdbc,ObjectMapper mapper,StoreService stores) { this.jdbc=jdbc;this.mapper=mapper;this.stores=stores; }
    @Override public String provider() { return "MOCK_SALES_AUTHORITY"; }
    @Override public Set<Capability> capabilities() { return Set.of(Capability.MOCK_ORDER_READ); }
    @Override public Snapshot authoritativeOrder(UUID tenantId,UUID storeId,String externalOrderId) {
        stores.requireActive(tenantId,storeId,false);
        return jdbc.query("select snapshot::text from mock_order_authority where tenant_id=? and store_id=? and external_order_id=?",(r,n)->{
            try { return mapper.readValue(r.getString(1),Snapshot.class); }
            catch(java.io.IOException ex) { throw new ApiException(409,"AUTHORITY_INVALID","Mock权威快照无效"); }
        },tenantId,storeId,externalOrderId).stream().findFirst().orElseThrow(()->new ApiException(409,"AUTHORITY_NOT_FOUND","等待Mock权威状态可用"));
    }
}
