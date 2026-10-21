package com.smartprocure.notification;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.*;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  public record Notification(
      UUID id,
      UUID storeId,
      UUID salesOrderId,
      String category,
      String code,
      Instant createdAt,
      Instant readAt,
      String mode) {}

  public record Activity(
      UUID id,
      UUID salesOrderId,
      String source,
      String action,
      String entityType,
      UUID entityId,
      String code,
      UUID actorId,
      Instant occurredAt,
      String mode) {}

  private final JdbcTemplate jdbc;
  private final M1Support support;
  private final StoreService stores;
  private final AuditService audit;

  public NotificationService(
      JdbcTemplate jdbc, M1Support support, StoreService stores, AuditService audit) {
    this.jdbc = jdbc;
    this.support = support;
    this.stores = stores;
    this.audit = audit;
  }

  private static Instant time(ResultSet r, String c) throws SQLException {
    Timestamp t = r.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  private Notification row(ResultSet r, int n) throws SQLException {
    return new Notification(
        r.getObject("id", UUID.class),
        r.getObject("store_id", UUID.class),
        r.getObject("sales_order_id", UUID.class),
        r.getString("category"),
        r.getString("code"),
        time(r, "created_at"),
        time(r, "read_at"),
        "MOCK");
  }

  private String join(TenantPrincipal p, List<Object> args) {
    args.add(p.userId());
    return " from notifications n join stores s on s.tenant_id=n.tenant_id and s.id=n.store_id left"
               + " join notification_reads nr on nr.tenant_id=n.tenant_id and"
               + " nr.notification_id=n.id and nr.user_id=? where "
        + support.scope(p, "s", args);
  }

  public M1Support.Page<Notification> list(
      TenantPrincipal p, UUID store, Boolean unread, int limit, int offset) {
    stores.requireDemo();
    M1Support.page(limit, offset);
    if (store != null) stores.get(p, store);
    var args = new ArrayList<Object>();
    String joins = join(p, args);
    if (store != null) {
      joins += " and n.store_id=?";
      args.add(store);
    }
    if (unread != null) joins += " and nr.read_at is " + (unread ? "null" : "not null");
    long count = jdbc.queryForObject("select count(*)" + joins, Long.class, args.toArray());
    args.add(limit);
    args.add(offset);
    return new M1Support.Page<>(
        jdbc.query(
            "select n.*,nr.read_at"
                + joins
                + " order by n.created_at desc,n.id desc limit ? offset ?",
            this::row,
            args.toArray()),
        count,
        limit,
        offset);
  }

  private Notification get(TenantPrincipal p, UUID id) {
    var args = new ArrayList<Object>();
    String joins = join(p, args);
    args.add(id);
    return jdbc
        .query("select n.*,nr.read_at" + joins + " and n.id=?", this::row, args.toArray())
        .stream()
        .findFirst()
        .orElseThrow(M1Support::missing);
  }

  @Transactional
  public Notification read(TenantPrincipal p, UUID id, String key) {
    stores.requireDemo();
    get(p, id);
    return support.command(
        p.currentTenantId(),
        key,
        "M4_NOTIFICATION_READ:" + p.userId() + ":" + id,
        Map.of("id", id, "user", p.userId()),
        Notification.class,
        () -> {
          jdbc.update(
              "insert into notification_reads(tenant_id,notification_id,user_id) values(?,?,?) on"
                  + " conflict do nothing",
              p.currentTenantId(),
              id,
              p.userId());
          audit.object(
              p.currentTenantId(), p.userId(), "MOCK_NOTIFICATION_READ", "NOTIFICATION", id);
          return get(p, id);
        });
  }

  public M1Support.Page<Activity> activity(TenantPrincipal p, UUID order, int limit, int offset) {
    stores.requireDemo();
    M1Support.page(limit, offset);
    UUID store =
        jdbc
            .query(
                "select store_id from sales_orders where tenant_id=? and id=?",
                (r, n) -> r.getObject(1, UUID.class),
                p.currentTenantId(),
                order)
            .stream()
            .findFirst()
            .orElseThrow(M1Support::missing);
    stores.get(p, store);
    long count =
        jdbc.queryForObject(
            "select count(*) from order_activities where tenant_id=? and sales_order_id=?",
            Long.class,
            p.currentTenantId(),
            order);
    return new M1Support.Page<>(
        jdbc.query(
            "select * from order_activities where tenant_id=? and sales_order_id=? order by"
                + " occurred_at desc,id desc limit ? offset ?",
            (r, n) ->
                new Activity(
                    r.getObject("id", UUID.class),
                    order,
                    r.getString("source"),
                    r.getString("action"),
                    r.getString("entity_type"),
                    r.getObject("entity_id", UUID.class),
                    r.getString("code"),
                    r.getObject("actor_id", UUID.class),
                    time(r, "occurred_at"),
                    "MOCK"),
            p.currentTenantId(),
            order,
            limit,
            offset),
        count,
        limit,
        offset);
  }
}
