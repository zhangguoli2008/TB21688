package com.smartprocure.store;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.M1Support;
import com.smartprocure.provider.RealChannelGuard;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

@Service
public class StoreService {
    public enum Platform { DOUYIN, TAOBAO }
    public enum AuthStatus { ACTIVE, EXPIRED, REVOKED }
    public record Create(@NotNull Platform platform,@NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9_-]+") String externalShopId,@NotBlank @Size(max=160) String displayName) { }
    public record Update(@NotNull @Min(0) Long version,@Size(min=1,max=160) String displayName,Boolean enabled,AuthStatus authStatus) { }
    public record Store(UUID id,String platform,String externalShopId,String displayName,boolean enabled,String authStatus,Instant authorizationExpiresAt,UUID integrationId,long version,String mode) { }
    public record Binding(UUID tenantId,UUID storeId,UUID integrationId) { }
    private final JdbcTemplate jdbc;
    private final M1Support support;
    private final RealChannelGuard guard;
    private final AuditService audit;
    public StoreService(JdbcTemplate jdbc,M1Support support,RealChannelGuard guard,AuditService audit) { this.jdbc=jdbc;this.support=support;this.guard=guard;this.audit=audit; }
    public static Store row(ResultSet r,int n) throws SQLException {
        Timestamp expiration=r.getTimestamp("authorization_expires_at"); Instant expires=expiration==null?null:expiration.toInstant();
        String status=r.getString("auth_status"); if(status.equals("ACTIVE")&&(expires==null||!expires.isAfter(Instant.now()))) status="EXPIRED";
        return new Store(r.getObject("id",UUID.class),r.getString("platform"),r.getString("external_shop_id"),r.getString("display_name"),r.getBoolean("enabled"),status,expires,r.getObject("integration_id",UUID.class),r.getLong("version"),"MOCK");
    }
    public M1Support.Page<Store> list(TenantPrincipal p,int limit,int offset) {
        M1Support.page(limit,offset); List<Object> args=new ArrayList<>(); String where=support.scope(p,"s",args);
        long total=jdbc.queryForObject("select count(*) from stores s where "+where,Long.class,args.toArray());
        args.add(limit);args.add(offset);
        return new M1Support.Page<>(jdbc.query("select s.* from stores s where "+where+" order by s.created_at desc,s.id desc limit ? offset ?",StoreService::row,args.toArray()),total,limit,offset);
    }
    public Store get(TenantPrincipal p,UUID id) {
        List<Object> args=new ArrayList<>();String where=support.scope(p,"s",args);args.add(id);
        return jdbc.query("select s.* from stores s where "+where+" and s.id=?",StoreService::row,args.toArray()).stream().findFirst().orElseThrow(M1Support::missing);
    }
    @Transactional public Store create(TenantPrincipal p,String key,Create input) {
        requireDemo();
        return support.command(p.currentTenantId(),key,"STORE_CREATE",input,Store.class,()->{
            if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from stores where tenant_id=? and platform=? and external_shop_id=?)",Boolean.class,p.currentTenantId(),input.platform().name(),input.externalShopId()))) throw M1Support.conflict("STORE_EXISTS");
            UUID id=UUID.randomUUID();
            int inserted=jdbc.update("insert into stores(id,tenant_id,platform,external_shop_id,display_name,auth_status,authorization_expires_at) values(?,?,?,?,?,'ACTIVE',now()+interval '30 days') on conflict(tenant_id,platform,external_shop_id) do nothing",id,p.currentTenantId(),input.platform().name(),input.externalShopId(),input.displayName().strip());
            if(inserted!=1)throw M1Support.conflict("STORE_EXISTS");
            Store created=get(p,id);audit.storeState(p.currentTenantId(),p.userId(),"MOCK_STORE_REGISTERED",id,created.version(),created.enabled(),created.authStatus());return created;
        });
    }
    @Transactional public Store update(TenantPrincipal p,UUID id,Update input) {
        requireDemo();Store before=get(p,id);
        if(input.displayName()!=null&&input.displayName().isBlank()) throw M1Support.invalid();
        String status=input.authStatus()==null?before.authStatus():input.authStatus().name();
        Instant expires=input.authStatus()==null?before.authorizationExpiresAt():switch(input.authStatus()) {
            case ACTIVE -> Instant.now().plusSeconds(30L*86400); case EXPIRED -> Instant.now().minusSeconds(1); case REVOKED -> null;
        };
        int count=jdbc.update("update stores set display_name=?,enabled=?,auth_status=?,authorization_expires_at=?,version=version+1,updated_at=now() where tenant_id=? and id=? and version=?",input.displayName()==null?before.displayName():input.displayName().strip(),input.enabled()==null?before.enabled():input.enabled(),status,expires==null?null:Timestamp.from(expires),p.currentTenantId(),id,input.version());
        if(count!=1)throw M1Support.conflict("VERSION_CONFLICT");
        Store updated=get(p,id);audit.storeState(p.currentTenantId(),p.userId(),"MOCK_STORE_UPDATED",id,updated.version(),updated.enabled(),updated.authStatus()); return updated;
    }
    public void requireDemo() { if(!guard.demoEnabled())throw M1Support.missing(); }
    public Binding integration(UUID integration) {
        requireDemo();
        return jdbc.query("select tenant_id,id,integration_id from stores where integration_id=?",(r,n)->new Binding(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),integration).stream().findFirst().orElseThrow(M1Support::missing);
    }
    /** Called again inside every writer transaction, locking the store against revocation races. */
    public Store requireActive(UUID tenant,UUID store,boolean lock) {
        requireDemo();guard.checkDatabaseFlags(tenant);
        // Non-key locks still serialize suspension/revocation, while permitting tenant/store FK references.
        var found=jdbc.query("select s.* from stores s join tenants t on t.id=s.tenant_id where s.tenant_id=? and s.id=? and t.status='ACTIVE'"+(lock?" for no key update of s,t":""),StoreService::row,tenant,store);
        if(found.isEmpty())throw M1Support.conflict("STORE_UNAVAILABLE");Store result=found.getFirst();
        if(!result.enabled()||!result.authStatus().equals("ACTIVE"))throw M1Support.conflict("AUTHORIZATION_UNAVAILABLE");return result;
    }
}
