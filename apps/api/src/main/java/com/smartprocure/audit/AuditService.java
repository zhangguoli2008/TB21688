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
}
