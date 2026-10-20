package com.smartprocure.audit;

import com.smartprocure.tenant.TenantPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** Fixed action names and response status only; no payload, cookies, token or free-form client input. */
    public void record(TenantPrincipal principal, String action, int status, String requestId) {
        jdbc.update("insert into audit_logs(tenant_id,actor_user_id,action,request_id,redacted_details) values (?,?,?,?,jsonb_build_object('outcome',?::text,'httpStatus',?::int))",
                principal == null ? null : principal.currentTenantId(), principal == null ? null : principal.userId(),
                action, requestId, status < 400 ? "SUCCESS" : "DENIED", status);
    }
    /** M1 fixed business actions. Never persist request bodies or exception messages. */
    public void object(java.util.UUID tenant,java.util.UUID actor,String action,String entityType,java.util.UUID entityId) {
        jdbc.update("insert into audit_logs(tenant_id,actor_user_id,action,entity_type,entity_id,redacted_details) values(?,?,?,?,?,'{}'::jsonb)",tenant,actor,action,entityType,entityId);
    }
    public void storeState(java.util.UUID tenant,java.util.UUID actor,String action,java.util.UUID store,long version,boolean enabled,String authStatus) {
        jdbc.update("insert into audit_logs(tenant_id,actor_user_id,action,entity_type,entity_id,redacted_details) values(?,?,?,'STORE',?,jsonb_build_object('version',?::bigint,'enabled',?::boolean,'authStatus',?::text))",tenant,actor,action,store,version,enabled,authStatus);
    }
}
