package com.smartprocure.common;

import com.smartprocure.audit.AuditService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Called in the same transaction as domain mutations. Contains no free-form customer data. */
@Component
public class M4Activity {
  private final JdbcTemplate jdbc;
  private final AuditService audit;

  public M4Activity(JdbcTemplate jdbc, AuditService audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  public void refresh(UUID tenant, UUID order) {
    jdbc.update(
        "insert into outbox_events(tenant_id,aggregate_id,event_type,payload) select"
            + " tenant_id,id,'M4_REPORT_REFRESH','{}'::jsonb from sales_orders where tenant_id=?"
            + " and id=?",
        tenant,
        order);
  }

  public void record(
      UUID tenant,
      UUID order,
      UUID actor,
      String source,
      String action,
      String entityType,
      UUID entity,
      String code,
      String eventKey,
      boolean notify) {
    UUID store =
        jdbc
            .query(
                "select store_id from sales_orders where tenant_id=? and id=?",
                (r, n) -> r.getObject(1, UUID.class),
                tenant,
                order)
            .stream()
            .findFirst()
            .orElseThrow(M1Support::missing);
    int added =
        jdbc.update(
            "insert into"
                + " order_activities(tenant_id,sales_order_id,store_id,actor_id,source,action,entity_type,entity_id,code,event_key)"
                + " values(?,?,?,?,?,?,?,?,?,?) on conflict(tenant_id,event_key) do nothing",
            tenant,
            order,
            store,
            actor,
            source,
            action,
            entityType,
            entity,
            code,
            eventKey);
    if (added == 0) return;
    if (notify)
      jdbc.update(
          "insert into notifications(tenant_id,sales_order_id,store_id,category,code,event_key)"
              + " values(?,?,?,?,?,?) on conflict(tenant_id,event_key) do nothing",
          tenant,
          order,
          store,
          source,
          code == null ? action : code,
          eventKey);
    audit.object(tenant, actor, action, entityType, entity);
    refresh(tenant, order);
  }
}
