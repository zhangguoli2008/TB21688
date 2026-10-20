package com.smartprocure.tenant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantIdentityRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public TenantIdentityRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public Optional<TenantPrincipal> findActive(UUID userId, UUID tenantId) {
        List<TenantPrincipal> found = jdbc.query("""
                select u.id,u.email,t.id as tenant_id,t.name,r.name as role_name,r.permissions::text
                from tenant_members m join users u on u.id=m.user_id join tenants t on t.id=m.tenant_id
                join roles r on r.id=m.role_id and r.tenant_id=m.tenant_id
                where m.user_id=? and m.tenant_id=? and m.status='ACTIVE' and u.status='ACTIVE' and t.status='ACTIVE'
                """, (rs, row) -> {
            try {
                return new TenantPrincipal(rs.getObject("id", UUID.class), rs.getString("email"), List.of(tenantId), tenantId,
                        rs.getString("name"), List.of(rs.getString("role_name")), mapper.readValue(rs.getString("permissions"), new TypeReference<List<String>>() {}), "MOCK");
            } catch (java.io.IOException ex) { throw new java.sql.SQLException("Invalid role permissions"); }
        }, userId, tenantId);
        return found.stream().findFirst();
    }
}
