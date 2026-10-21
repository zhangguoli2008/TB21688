package com.smartprocure.procurement;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.M1Support;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@Service
public class ProcurementPolicyService {
    public record Policy(boolean enabled,long maxSingleFen,long maxDailyFen,long maxShippingFen,long minProfitFen,long platformFeeFen,long reserveFen,String region,long version,String mode) { }
    public record Update(@NotNull Boolean enabled,@NotNull @Min(0) @Max(1000000000000L) Long maxSingleFen,@NotNull @Min(0) @Max(1000000000000L) Long maxDailyFen,@NotNull @Min(0) @Max(1000000000L) Long maxShippingFen,@NotNull @Min(0) @Max(1000000000L) Long minProfitFen,@NotNull @Min(0) @Max(1000000000L) Long platformFeeFen,@NotNull @Min(0) @Max(1000000000L) Long reserveFen,@NotNull @Pattern(regexp="CN") String region,@NotNull @Min(0) @Max(9007199254740991L) Long version) { }
    public record PolicySnapshot(Policy tenantPolicy,Policy storePolicy,Policy effective) {
        public long tenantVersion() { return tenantPolicy.version(); }
        public long storeVersion() { return storePolicy.version(); }
    }
    private final JdbcTemplate jdbc;
    private final StoreService stores;
    private final AuditService audit;
    public ProcurementPolicyService(JdbcTemplate jdbc,StoreService stores,AuditService audit) { this.jdbc=jdbc;this.stores=stores;this.audit=audit; }
    private static Policy row(ResultSet r,int n)throws SQLException { return new Policy(r.getBoolean("enabled"),r.getLong("max_single_fen"),r.getLong("max_daily_fen"),r.getLong("max_shipping_fen"),r.getLong("min_profit_fen"),r.getLong("platform_fee_fen"),r.getLong("reserve_fen"),r.getString("region"),r.getLong("version"),"MOCK"); }
    private void ensure(UUID tenant,UUID store) {
        jdbc.update("insert into procurement_policies(tenant_id,store_id,max_single_fen,max_daily_fen) values(?,?,?,?) on conflict do nothing",tenant,store,store==null?100000L:50000L,store==null?1000000L:200000L);
    }
    public Policy tenant(UUID tenant) { ensure(tenant,null);return read(tenant,null,false); }
    public Policy store(UUID tenant,UUID store) { ensure(tenant,store);return read(tenant,store,false); }
    private Policy read(UUID tenant,UUID store,boolean lock) {
        return jdbc.queryForObject("select * from procurement_policies where tenant_id=? and store_id is not distinct from ?::uuid"+(lock?" for update":""),ProcurementPolicyService::row,tenant,store);
    }
    public PolicySnapshot getEffective(UUID tenant,UUID store) { return combine(tenant(tenant),store(tenant,store)); }
    /** Call inside a transaction; every creator takes tenant before store to serialize daily reservations. */
    public PolicySnapshot lock(UUID tenant,UUID store) {
        ensure(tenant,null);Policy tenantPolicy=read(tenant,null,true);ensure(tenant,store);return combine(tenantPolicy,read(tenant,store,true));
    }
    private static PolicySnapshot combine(Policy t,Policy s) {
        Policy effective=new Policy(t.enabled()&&s.enabled(),Math.min(t.maxSingleFen(),s.maxSingleFen()),Math.min(t.maxDailyFen(),s.maxDailyFen()),Math.min(t.maxShippingFen(),s.maxShippingFen()),Math.max(t.minProfitFen(),s.minProfitFen()),Math.max(t.platformFeeFen(),s.platformFeeFen()),Math.max(t.reserveFen(),s.reserveFen()),s.region(),s.version(),"MOCK");
        return new PolicySnapshot(t,s,effective);
    }
    public Policy get(TenantPrincipal p,UUID store) { stores.requireDemo();if(store!=null)stores.get(p,store);return store==null?tenant(p.currentTenantId()):store(p.currentTenantId(),store); }
    @Transactional public Policy update(TenantPrincipal p,UUID store,Update input) {
        stores.requireDemo();if(store!=null)stores.get(p,store);ensure(p.currentTenantId(),store);
        int count=jdbc.update("update procurement_policies set enabled=?,max_single_fen=?,max_daily_fen=?,max_shipping_fen=?,min_profit_fen=?,platform_fee_fen=?,reserve_fen=?,region=?,version=version+1,updated_at=now() where tenant_id=? and store_id is not distinct from ?::uuid and version=?",input.enabled(),input.maxSingleFen(),input.maxDailyFen(),input.maxShippingFen(),input.minProfitFen(),input.platformFeeFen(),input.reserveFen(),input.region(),p.currentTenantId(),store,input.version());
        if(count!=1)throw M1Support.conflict("VERSION_CONFLICT");
        UUID id=jdbc.queryForObject("select id from procurement_policies where tenant_id=? and store_id is not distinct from ?::uuid",UUID.class,p.currentTenantId(),store);
        audit.object(p.currentTenantId(),p.userId(),"MOCK_PROCUREMENT_POLICY_UPDATED","PROCUREMENT_POLICY",id);return read(p.currentTenantId(),store,false);
    }
}
