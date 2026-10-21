package com.smartprocure.reporting;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.*;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportingService {
  public record Refresh(String state, long queuedCount, String mode) {}

  private record Range(Instant from, Instant to) {}

  private final JdbcTemplate jdbc;
  private final M1Support support;
  private final StoreService stores;
  private final M4Activity activity;
  private final AuditService audit;

  public ReportingService(
      JdbcTemplate jdbc,
      M1Support support,
      StoreService stores,
      M4Activity activity,
      AuditService audit) {
    this.jdbc = jdbc;
    this.support = support;
    this.stores = stores;
    this.activity = activity;
    this.audit = audit;
  }

  private Range range(Instant from, Instant to) {
    Instant
        end =
            to == null
                ? jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant()
                : to,
        start = from == null ? end.minus(Duration.ofDays(30)) : from;
    if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0)
      throw M1Support.invalid();
    return new Range(start, end);
  }

  private String where(TenantPrincipal p, UUID store, Range range, List<Object> args) {
    stores.requireDemo();
    if (store != null) stores.get(p, store);
    String s = support.scope(p, "s", args);
    args.add(Timestamp.from(range.from()));
    args.add(Timestamp.from(range.to()));
    s += " and o.created_at>=? and o.created_at<?";
    if (store != null) {
      args.add(store);
      s += " and o.store_id=?";
    }
    return s;
  }

  private static final String JOINS =
      " from sales_orders o join stores s on s.tenant_id=o.tenant_id and s.id=o.store_id left join"
          + " report_order_projections p on p.tenant_id=o.tenant_id and p.sales_order_id=o.id where"
          + " ";
  private static final String[] METRICS = {
    "sales_paid_fen",
    "purchase_paid_fen",
    "estimated_purchase_fen",
    "estimated_profit_fen",
    "sales_refunded_fen",
    "supplier_refunded_fen",
    "unreconciled_count",
    "unreconciled_payment_fen",
    "pending_supplier_refund_fen"
  };
  private static final String[] NAMES = {
    "salesPaidFen",
    "purchasePaidFen",
    "estimatedPurchaseFen",
    "estimatedProfitFen",
    "salesRefundedFen",
    "supplierRefundedFen",
    "unreconciledCount",
    "unreconciledPaymentFen",
    "pendingSupplierRefundFen"
  };

  private Map<String, Object> amounts(ResultSet r) throws SQLException {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i < METRICS.length; i++) m.put(NAMES[i], r.getLong(METRICS[i]));
    m.put(
        "netCashFlowFen",
        Math.addExact(
            Math.subtractExact(
                Math.subtractExact((long) m.get("salesPaidFen"), (long) m.get("salesRefundedFen")),
                (long) m.get("purchasePaidFen")),
            (long) m.get("supplierRefundedFen")));
    m.put("actualNetProfitFen", null);
    return m;
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Map<String, Object> overview(
      TenantPrincipal principal, UUID store, Instant from, Instant to) {
    Range range = range(from, to);
    var args = new ArrayList<Object>();
    String where = where(principal, store, range, args);
    String sums =
        String.join(
            ",",
            Arrays.stream(METRICS).map(n -> "coalesce(sum(p." + n + "),0)::bigint " + n).toList());
    return jdbc.queryForObject(
        "select "
            + sums
            + ",count(*) order_count,min(p.as_of) as_of,count(*) filter(where p.sales_order_id is"
            + " null or exists(select 1 from outbox_events e where e.tenant_id=o.tenant_id and"
            + " e.aggregate_id=o.id and e.event_type='M4_REPORT_REFRESH' and e.state<>'DONE'))"
            + " pending"
            + JOINS
            + where,
        (r, n) -> {
          var m = amounts(r);
          m.put("orderCount", r.getLong("order_count"));
          m.put("profitStatus", "INCOMPLETE_COSTS");
          m.put("currency", "CNY");
          m.put("basis", "ORDER_CREATED_COHORT");
          m.put("fromTime", range.from());
          m.put("toTime", range.to());
          Timestamp asOf = r.getTimestamp("as_of");
          m.put("asOf", asOf == null ? Instant.now() : asOf.toInstant());
          m.put("projectionPendingCount", r.getLong("pending"));
          m.put("mode", "MOCK");
          return m;
        },
        args.toArray());
  }

  public M1Support.Page<Map<String, Object>> orders(
      TenantPrincipal p, UUID store, Instant from, Instant to, int limit, int offset) {
    M1Support.page(limit, offset);
    Range range = range(from, to);
    var args = new ArrayList<Object>();
    String where = where(p, store, range, args);
    long count =
        jdbc.queryForObject(
            "select count(*)" + JOINS + where + " and p.sales_order_id is not null",
            Long.class,
            args.toArray());
    args.add(limit);
    args.add(offset);
    return new M1Support.Page<>(
        jdbc.query(
            "select p.*"
                + JOINS
                + where
                + " and p.sales_order_id is not null order by o.created_at desc,o.id desc limit ?"
                + " offset ?",
            (r, n) -> {
              var m = amounts(r);
              m.put("salesOrderId", r.getObject("sales_order_id", UUID.class));
              m.put("storeId", r.getObject("store_id", UUID.class));
              m.put("externalOrderId", r.getString("external_order_id"));
              m.put("createdAt", r.getTimestamp("order_created_at").toInstant());
              m.put("asOf", r.getTimestamp("as_of").toInstant());
              m.put("mode", "MOCK");
              return m;
            },
            args.toArray()),
        count,
        limit,
        offset);
  }

  @Transactional
  public Refresh refresh(TenantPrincipal p, String key) {
    stores.requireDemo();
    return support.command(
        p.currentTenantId(),
        key,
        "M4_REPORT_REFRESH_REQUEST",
        Map.of("tenant", p.currentTenantId()),
        Refresh.class,
        () -> {
          var rows =
              jdbc.query(
                  "select id from sales_orders where tenant_id=? order by created_at desc,id limit"
                      + " 1000",
                  (r, n) -> r.getObject(1, UUID.class),
                  p.currentTenantId());
          for (UUID order : rows) activity.refresh(p.currentTenantId(), order);
          audit.object(
              p.currentTenantId(),
              p.userId(),
              "MOCK_REPORT_REFRESH_REQUESTED",
              "TENANT",
              p.currentTenantId());
          return new Refresh("QUEUED", rows.size(), "MOCK");
        });
  }
}
