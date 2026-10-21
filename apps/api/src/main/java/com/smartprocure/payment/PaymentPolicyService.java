package com.smartprocure.payment;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.M1Support;
import com.smartprocure.provider.RealChannelGuard;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.validation.constraints.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class PaymentPolicyService {
    public enum Mode { MANUAL,AUTO,RULE_BASED }
    public enum MandateState { ACTIVE,EXPIRED,REVOKED }
    public record Update(@NotNull Mode mode,@NotNull @Min(0) @Max(1000000000000L) Long maxSingleFen,@NotNull @Min(0) @Max(1000000000000L) Long maxDailyFen,@NotNull @Min(0) @Max(1000000000000L) Long maxStoreSingleFen,@NotNull @Min(0) @Max(1000000000000L) Long maxStoreDailyFen,@NotNull @Min(0) @Max(1000000000000L) Long minEstimatedProfitFen,@NotNull Boolean isPaused,@NotNull Boolean newSupplierFirstOrderManual,@NotNull @Min(0) @Max(9007199254740991L) Long version) { }
    public record Policy(String mode,long maxSingleFen,long maxDailyFen,long maxStoreSingleFen,long maxStoreDailyFen,long minEstimatedProfitFen,boolean isPaused,boolean newSupplierFirstOrderManual,long version,boolean globalPaused,long usedDailyFen,String runtimeMode) { }
    public record MandateUpdate(@NotNull MandateState status,@NotNull @Min(0) @Max(1000000000000L) Long maxSingleFen,@NotNull @Min(0) @Max(9007199254740991L) Long version) { }
    public record Mandate(UUID id,UUID supplierAccountId,String supplierAccountName,String status,long maxSingleFen,Instant expiresAt,Instant lastVerifiedAt,long version,String runtimeMode) { }
    private final JdbcTemplate jdbc;private final M1Support support;private final StoreService stores;private final AuditService audit;private final Environment environment;private final RealChannelGuard guard;private final Clock clock;
    public PaymentPolicyService(JdbcTemplate jdbc,M1Support support,StoreService stores,AuditService audit,Environment environment,RealChannelGuard guard,Clock clock){this.jdbc=jdbc;this.support=support;this.stores=stores;this.audit=audit;this.environment=environment;this.guard=guard;this.clock=clock;}
    public boolean globalPaused(){return environment.getProperty("app.payment-emergency-stop",Boolean.class,false)||Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from feature_flags where tenant_id is null and flag_name='payment_emergency_stop' and enabled)",Boolean.class));}
    public void allowed(UUID tenant){stores.requireDemo();guard.checkDatabaseFlags(tenant);if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tenants where id=? and status='ACTIVE')",Boolean.class,tenant)))throw M1Support.conflict("TENANT_UNAVAILABLE");}
    Instant now(){return clock.instant();}
    public long used(UUID tenant,UUID store){return used(tenant,store,now().atZone(ZoneOffset.UTC).toLocalDate());}
    long used(UUID tenant,UUID store,LocalDate date){return jdbc.queryForObject("select coalesce(sum(b.reserved_fen),0) from payment_budget_reservations b join payment_attempts a on a.tenant_id=b.tenant_id and a.id=b.payment_attempt_id join purchase_orders p on p.tenant_id=a.tenant_id and p.id=a.purchase_order_id join purchase_intents i on i.tenant_id=p.tenant_id and i.id=p.purchase_intent_id join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id join sales_orders o on o.tenant_id=si.tenant_id and o.id=si.sales_order_id where b.tenant_id=? and b.budget_date=? and b.state<>'RELEASED'"+(store==null?"":" and o.store_id=?"),Long.class,store==null?new Object[]{tenant,java.sql.Date.valueOf(date)}:new Object[]{tenant,java.sql.Date.valueOf(date),store});}
    private Policy row(ResultSet r,int n)throws SQLException{return new Policy(r.getString("mode"),r.getLong("max_single_fen"),r.getLong("max_daily_fen"),r.getLong("max_store_single_fen"),r.getLong("max_store_daily_fen"),r.getLong("min_estimated_profit_fen"),r.getBoolean("is_paused"),r.getBoolean("new_supplier_first_order_manual"),r.getLong("version"),globalPaused(),used(r.getObject("tenant_id",UUID.class),null),"MOCK");}
    public Policy get(TenantPrincipal p){allowed(p.currentTenantId());return get(p.currentTenantId(),false);}
    private Policy get(UUID tenant,boolean lock){return jdbc.query("select * from payment_policies where tenant_id=?"+(lock?" for update":""),this::row,tenant).stream().findFirst().orElseThrow(M1Support::missing);}
    public Policy lock(UUID tenant){allowed(tenant);jdbc.update("insert into payment_policies(tenant_id,max_single_fen,max_daily_fen) values(?,50000,200000) on conflict do nothing",tenant);return get(tenant,true);}
    @Transactional public Policy update(TenantPrincipal p,String key,Update u){allowed(p.currentTenantId());lock(p.currentTenantId());return support.command(p.currentTenantId(),key,"PAYMENT_POLICY_UPDATE",u,Policy.class,()->{
        int n=jdbc.update("update payment_policies set mode=?,max_single_fen=?,max_daily_fen=?,max_store_single_fen=?,max_store_daily_fen=?,min_estimated_profit_fen=?,is_paused=?,new_supplier_first_order_manual=?,version=version+1,updated_at=now() where tenant_id=? and version=? and version<9007199254740991",u.mode().name(),u.maxSingleFen(),u.maxDailyFen(),u.maxStoreSingleFen(),u.maxStoreDailyFen(),u.minEstimatedProfitFen(),u.isPaused(),u.newSupplierFirstOrderManual(),p.currentTenantId(),u.version());if(n!=1)throw M1Support.conflict("VERSION_CONFLICT");audit.object(p.currentTenantId(),p.userId(),"MOCK_PAYMENT_POLICY_UPDATED","TENANT",p.currentTenantId());return get(p.currentTenantId(),false);
    });}
    static Instant instant(ResultSet r,String column)throws SQLException{Timestamp t=r.getTimestamp(column);return t==null?null:t.toInstant();}
    private Mandate mandate(ResultSet r,int n)throws SQLException{Instant expiry=instant(r,"expires_at");String state=r.getString("status");if("ACTIVE".equals(state)&&(expiry==null||!expiry.isAfter(now())))state="EXPIRED";return new Mandate(r.getObject("id",UUID.class),r.getObject("supplier_account_id",UUID.class),r.getString("external_account_id"),state,r.getLong("max_single_fen"),expiry,instant(r,"last_verified_at"),r.getLong("version"),"MOCK");}
    public M1Support.Page<Mandate> mandates(TenantPrincipal p,int limit,int offset){
        allowed(p.currentTenantId());M1Support.page(limit,offset);List<Object> args=new ArrayList<>();args.add(p.currentTenantId());String where="m.tenant_id=?";
        if(!M1Support.owner(p)){where+=" and exists(select 1 from supplier_offers f join sku_mappings sm on sm.tenant_id=f.tenant_id and sm.supplier_offer_id=f.id join member_store_access msa on msa.tenant_id=sm.tenant_id and msa.store_id=sm.store_id join tenant_members tm on tm.tenant_id=msa.tenant_id and tm.id=msa.tenant_member_id where f.tenant_id=m.tenant_id and f.supplier_account_id=m.supplier_account_id and tm.user_id=? and tm.status='ACTIVE')";args.add(p.userId());}
        String joins=" from payment_mandates m join supplier_accounts a on a.tenant_id=m.tenant_id and a.id=m.supplier_account_id where "+where;long total=jdbc.queryForObject("select count(*)"+joins,Long.class,args.toArray());args.add(limit);args.add(offset);return new M1Support.Page<>(jdbc.query("select m.*,a.external_account_id"+joins+" order by m.created_at,m.id limit ? offset ?",this::mandate,args.toArray()),total,limit,offset);
    }
    @Transactional public Mandate updateMandate(TenantPrincipal p,UUID id,MandateUpdate u){allowed(p.currentTenantId());lock(p.currentTenantId());if(jdbc.query("select id from payment_mandates where tenant_id=? and id=?",(r,n)->r.getObject(1,UUID.class),p.currentTenantId(),id).isEmpty())throw M1Support.missing();Instant expires=switch(u.status()){case ACTIVE->now().plusSeconds(30L*86400);case EXPIRED->now().minusSeconds(1);case REVOKED->null;};
        int n=jdbc.update("update payment_mandates set status=?,max_single_fen=?,expires_at=?,last_verified_at=now(),version=version+1 where tenant_id=? and id=? and version=? and version<9007199254740991",u.status().name(),u.maxSingleFen(),expires==null?null:Timestamp.from(expires),p.currentTenantId(),id,u.version());if(n!=1)throw M1Support.conflict("VERSION_CONFLICT");audit.object(p.currentTenantId(),p.userId(),"MOCK_PAYMENT_MANDATE_UPDATED","PAYMENT_MANDATE",id);return jdbc.queryForObject("select m.*,a.external_account_id from payment_mandates m join supplier_accounts a on a.tenant_id=m.tenant_id and a.id=m.supplier_account_id where m.tenant_id=? and m.id=?",this::mandate,p.currentTenantId(),id);
    }
}
