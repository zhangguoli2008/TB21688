package com.smartprocure.aftersales;

import com.smartprocure.common.*;
import com.smartprocure.logistics.ShipmentService;
import com.smartprocure.provider.*;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.validation.constraints.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AftersalesService {
  public enum Type {
    CANCEL,
    REFUND_ONLY,
    RETURN_REFUND
  }

  public enum SourceState {
    REQUESTED,
    CANCELLED,
    REFUNDING,
    REFUNDED,
    REJECTED,
    ARBITRATION
  }

  public enum Upstream {
    NO_PURCHASE,
    PENDING,
    CANCEL_REQUESTED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED,
    RETURN_REQUIRED,
    RETURN_RECEIVED,
    MANUAL_REVIEW,
    QUERY_PAYMENT,
    UNKNOWN,
    REJECTED
  }

  public enum Action {
    ACKNOWLEDGE,
    RETURN_SENT,
    REQUEST_SUPPLIER_CANCEL,
    REQUEST_SUPPLIER_REFUND
  }

  public record Event(
      @NotBlank @Pattern(regexp = "MOCK-[A-Za-z0-9_-]{1,100}") String eventId,
      @NotBlank @Pattern(regexp = "MOCK-CASE-[A-Za-z0-9_-]{1,100}") String sourceCaseId,
      @NotNull Type type,
      @NotNull SourceState sourceState,
      @Min(1) long sourceVersion,
      @NotNull UUID salesOrderItemId,
      @Min(1) @Max(10000) int qty,
      @NotNull @Min(0) Long requestedRefundFen) {}

  public record SupplierEvent(
      @NotBlank @Pattern(regexp = "MOCK-[A-Za-z0-9_-]{1,100}") String eventId,
      @NotNull UUID purchaseOrderId,
      @Min(1) long sourceVersion,
      @NotNull Upstream state,
      @NotNull @Min(0) Long refundAmountFen) {}

  public record Update(@NotNull Action action, @NotNull @Min(0) Long version) {}

  private final JdbcTemplate jdbc;
  private final M1Support support;
  private final StoreService stores;
  private final ShipmentService shipments;
  private final M4Activity activity;

  public AftersalesService(
      JdbcTemplate j, M1Support s, StoreService st, ShipmentService sh, M4Activity a) {
    jdbc = j;
    support = s;
    stores = st;
    shipments = sh;
    activity = a;
  }

  private UUID order(TenantPrincipal p, UUID id, boolean lock) {
    UUID store =
        jdbc
            .query(
                "select store_id from sales_orders where tenant_id=? and id=?",
                (r, n) -> r.getObject(1, UUID.class),
                p.currentTenantId(),
                id)
            .stream()
            .findFirst()
            .orElseThrow(M1Support::missing);
    stores.get(p, store);
    if (lock) {
      stores.requireActive(p.currentTenantId(), store, true);
      jdbc.query(
          "select id from sales_orders where tenant_id=? and id=? for no key update",
          (r, n) -> r.getObject(1),
          p.currentTenantId(),
          id);
    }
    return store;
  }

  private ShipmentService.Accepted accepted(
      UUID tenant,
      UUID aggregate,
      String event,
      String kind,
      Object input,
      Map<String, Object> payload) {
    String digest = M1Support.digest(support.json(input));
    var duplicate = shipments.duplicate(tenant, event, digest, kind, aggregate);
    if (duplicate != null) return duplicate;
    UUID job = UUID.randomUUID();
    jdbc.update(
        "insert into m4_mock_events(tenant_id,event_id,event_type,aggregate_id,digest,job_id)"
            + " values(?,?,?,?,?,?)",
        tenant,
        event,
        kind,
        aggregate,
        digest,
        job);
    jdbc.update(
        "insert into outbox_events(id,tenant_id,aggregate_id,event_type,payload)"
            + " values(?,?,?,?,?::jsonb)",
        job,
        tenant,
        aggregate,
        kind,
        support.json(payload));
    return new ShipmentService.Accepted(event, job, "QUEUED", "MOCK");
  }

  @Transactional
  public ShipmentService.Accepted event(TenantPrincipal p, UUID order, String key, Event e) {
    UUID store = order(p, order, true);
    return support.command(
        p.currentTenantId(),
        key,
        "M4_AFTERSALE_EVENT:" + order,
        e,
        ShipmentService.Accepted.class,
        () -> {
          boolean conflictingOrder =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "select exists(select 1 from mock_aftersale_authority a join sales_orders o"
                          + " on o.tenant_id=a.tenant_id and o.id=a.sales_order_id where"
                          + " a.tenant_id=? and o.store_id=? and a.source_case_id=? and"
                          + " a.sales_order_id<>?)",
                      Boolean.class,
                      p.currentTenantId(),
                      store,
                      e.sourceCaseId(),
                      order));
          if (conflictingOrder) throw M1Support.conflict("AFTERSALE_CASE_ORDER_CONFLICT");
          var item =
              jdbc
                  .query(
                      "select qty,paid_allocated_fen from sales_order_items where tenant_id=? and"
                          + " sales_order_id=? and id=? for no key update",
                      (r, n) -> new long[] {r.getLong(1), r.getLong(2)},
                      p.currentTenantId(),
                      order,
                      e.salesOrderItemId())
                  .stream()
                  .findFirst()
                  .orElseThrow(M1Support::missing);
          if (e.qty() > item[0]
              || e.requestedRefundFen()
                  > java.math.BigInteger.valueOf(item[1])
                      .multiply(java.math.BigInteger.valueOf(e.qty()))
                      .divide(java.math.BigInteger.valueOf(item[0]))
                      .longValueExact()) throw M1Support.conflict("REFUND_AMOUNT_EXCEEDED");
          var old =
              jdbc.query(
                  "select snapshot::text,source_version from mock_aftersale_authority where"
                      + " tenant_id=? and sales_order_id=? and source_case_id=? for update",
                  (r, n) -> new Object[] {r.getString(1), r.getLong(2)},
                  p.currentTenantId(),
                  order,
                  e.sourceCaseId());
          String sourceJson =
              support.json(
                  new AftersalesProviderAdapter.Source(
                      e.eventId(),
                      e.sourceCaseId(),
                      e.type().name(),
                      e.sourceState().name(),
                      e.sourceVersion(),
                      e.salesOrderItemId(),
                      e.qty(),
                      e.requestedRefundFen()));
          if (!old.isEmpty()) {
            try {
              var existing =
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .readTree((String) old.getFirst()[0]);
              boolean identity =
                  !existing.get("salesOrderItemId").asText().equals(e.salesOrderItemId().toString())
                      || !existing.get("type").asText().equals(e.type().name())
                      || existing.get("qty").asInt() != e.qty()
                      || existing.get("requestedRefundFen").asLong() != e.requestedRefundFen();
              if (identity) throw M1Support.conflict("AFTERSALE_IDENTITY_CHANGED");
              if ((long) old.getFirst()[1] == e.sourceVersion()
                  && !existing.get("sourceState").asText().equals(e.sourceState().name()))
                throw M1Support.conflict("AUTHORITY_VERSION_CONFLICT");
              if ((long) old.getFirst()[1] < e.sourceVersion()
                  && Set.of("REFUNDED", "REJECTED").contains(existing.get("sourceState").asText())
                  && !existing.get("sourceState").asText().equals(e.sourceState().name()))
                throw M1Support.conflict("AFTERSALE_TERMINAL");
            } catch (java.io.IOException ex) {
              throw new IllegalStateException(ex);
            }
          }
          var result =
              accepted(
                  p.currentTenantId(),
                  order,
                  e.eventId(),
                  "M4_AFTERSALE_SOURCE",
                  e,
                  Map.of("sourceCaseId", e.sourceCaseId()));
          jdbc.update(
              "insert into"
                  + " mock_aftersale_authority(tenant_id,sales_order_id,source_case_id,source_version,snapshot)"
                  + " values(?,?,?,?,?::jsonb) on conflict(tenant_id,sales_order_id,source_case_id)"
                  + " do update set"
                  + " source_version=excluded.source_version,snapshot=excluded.snapshot where"
                  + " mock_aftersale_authority.source_version<excluded.source_version",
              p.currentTenantId(),
              order,
              e.sourceCaseId(),
              e.sourceVersion(),
              sourceJson);
          refreshHold(p.currentTenantId(), e.salesOrderItemId());
          activity.record(
              p.currentTenantId(),
              order,
              p.userId(),
              "AFTERSALES",
              "MOCK_AFTERSALE_EVENT_ACCEPTED",
              "SALES_ORDER",
              order,
              null,
              e.eventId(),
              false);
          return result;
        });
  }

  /**
   * Accepted synthetic source evidence fences launches immediately, before the authority-consumer
   * catches up.
   */
  public void refreshHold(UUID tenant, UUID item) {
    long total =
        jdbc.queryForObject(
            "select coalesce(sum((snapshot->>'qty')::int),0) from mock_aftersale_authority where"
                + " tenant_id=? and snapshot->>'salesOrderItemId'=? and"
                + " snapshot->>'sourceState'<>'REJECTED'",
            Long.class,
            tenant,
            item.toString());
    int qty =
        jdbc.queryForObject(
            "select qty from sales_order_items where tenant_id=? and id=?",
            Integer.class,
            tenant,
            item);
    if (total > qty) throw M1Support.conflict("AFTERSALE_QUANTITY_EXCEEDED");
    jdbc.update(
        "update sales_order_items set aftersale_hold_qty=?,version=version+1 where tenant_id=? and"
            + " id=?",
        total,
        tenant,
        item);
  }

  @Transactional
  public ShipmentService.Accepted supplierEvent(
      TenantPrincipal p, UUID id, String key, SupplierEvent e) {
    var c = detail(p, id);
    UUID order = (UUID) c.get("salesOrderId");
    order(p, order, true);
    return support.command(
        p.currentTenantId(),
        key,
        "M4_SUPPLIER_AFTERSALE:" + id,
        e,
        ShipmentService.Accepted.class,
        () -> {
          var po = shipments.purchase(p.currentTenantId(), e.purchaseOrderId());
          if (!po.item().equals(c.get("salesOrderItemId"))) throw M1Support.missing();
          var co =
              jdbc
                  .query(
                      "select requested_refund_fen from aftersales_coordinations where tenant_id=?"
                          + " and aftersales_case_id=? and purchase_order_id=? for update",
                      (r, n) -> r.getLong(1),
                      p.currentTenantId(),
                      id,
                      e.purchaseOrderId())
                  .stream()
                  .findFirst()
                  .orElseThrow(M1Support::missing);
          if (e.refundAmountFen() > co) throw M1Support.conflict("REFUND_AMOUNT_EXCEEDED");
          var previous =
              jdbc.query(
                  "select source_version,snapshot->>'state',(snapshot->>'refundAmountFen')::bigint"
                      + " from mock_supplier_aftersale_authority where tenant_id=? and"
                      + " aftersales_case_id=? and purchase_order_id=? for update",
                  (r, n) -> new Object[] {r.getLong(1), r.getString(2), r.getLong(3)},
                  p.currentTenantId(),
                  id,
                  e.purchaseOrderId());
          if (!previous.isEmpty()
              && (long) previous.getFirst()[0] == e.sourceVersion()
              && (!previous.getFirst()[1].equals(e.state().name())
                  || (long) previous.getFirst()[2] != e.refundAmountFen()))
            throw M1Support.conflict("AUTHORITY_VERSION_CONFLICT");
          var result =
              accepted(
                  p.currentTenantId(),
                  id,
                  e.eventId(),
                  "M4_AFTERSALE_SUPPLIER",
                  e,
                  Map.of("purchaseOrderId", e.purchaseOrderId()));
          var snapshot =
              new AftersalesProviderAdapter.Supplier(
                  e.eventId(),
                  e.purchaseOrderId(),
                  e.sourceVersion(),
                  e.state().name(),
                  e.refundAmountFen());
          jdbc.update(
              "insert into"
                  + " mock_supplier_aftersale_authority(tenant_id,aftersales_case_id,purchase_order_id,source_version,snapshot)"
                  + " values(?,?,?,?,?::jsonb) on"
                  + " conflict(tenant_id,aftersales_case_id,purchase_order_id) do update set"
                  + " source_version=excluded.source_version,snapshot=excluded.snapshot where"
                  + " mock_supplier_aftersale_authority.source_version<excluded.source_version",
              p.currentTenantId(),
              id,
              e.purchaseOrderId(),
              e.sourceVersion(),
              support.json(snapshot));
          activity.record(
              p.currentTenantId(),
              order,
              p.userId(),
              "AFTERSALES",
              "MOCK_SUPPLIER_AFTERSALE_EVENT_ACCEPTED",
              "AFTERSALE",
              id,
              null,
              e.eventId(),
              false);
          return result;
        });
  }

  static Instant instant(ResultSet r, String n) throws SQLException {
    var t = r.getTimestamp(n);
    return t == null ? null : t.toInstant();
  }

  public Map<String, Object> row(ResultSet r) throws SQLException {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", r.getObject("id"));
    m.put("salesOrderId", r.getObject("sales_order_id"));
    m.put("salesOrderItemId", r.getObject("sales_order_item_id"));
    m.put("storeId", r.getObject("store_id"));
    m.put("sourceCaseId", r.getString("source_case_id"));
    m.put("type", r.getString("type"));
    m.put("sourceState", r.getString("source_state"));
    m.put("sourceVersion", r.getLong("source_version"));
    m.put("qty", r.getInt("qty"));
    m.put("requestedRefundFen", r.getLong("requested_refund_fen"));
    m.put("currency", r.getString("currency"));
    m.put("state", r.getString("state"));
    m.put("upstreamState", r.getString("upstream_state"));
    m.put("deadlineAt", instant(r, "deadline_at"));
    m.put("reasonCode", r.getString("reason_code"));
    m.put("version", r.getLong("version"));
    m.put("createdAt", instant(r, "created_at"));
    m.put("updatedAt", instant(r, "updated_at"));
    m.put("mode", "MOCK");
    return m;
  }

  public Map<String, Object> base(UUID tenant, UUID id) {
    return jdbc
        .query(
            "select * from aftersales_cases where tenant_id=? and id=?",
            (r, n) -> row(r),
            tenant,
            id)
        .stream()
        .findFirst()
        .orElseThrow(M1Support::missing);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(TenantPrincipal p, UUID id) {
    stores.requireDemo();
    var m = base(p.currentTenantId(), id);
    order(p, (UUID) m.get("salesOrderId"), false);
    m.put(
        "coordinations",
        jdbc.query(
            "select * from aftersales_coordinations where tenant_id=? and aftersales_case_id=?"
                + " order by id",
            (r, n) -> {
              var v = new LinkedHashMap<String, Object>();
              v.put("id", r.getObject("id"));
              v.put("purchaseOrderId", r.getObject("purchase_order_id"));
              v.put("state", r.getString("state"));
              v.put("upstreamSourceState", r.getString("upstream_source_state"));
              v.put("requestedRefundFen", r.getLong("requested_refund_fen"));
              v.put("cancellationReference", r.getString("cancellation_reference"));
              v.put("refundReference", r.getString("refund_reference"));
              v.put("updatedAt", instant(r, "updated_at"));
              v.put("version", r.getLong("version"));
              return v;
            },
            p.currentTenantId(),
            id));
    m.put(
        "refunds",
        jdbc.query(
            "select * from refund_transactions where tenant_id=? and aftersales_case_id=? order by"
                + " created_at,id",
            (r, n) -> {
              var v = new LinkedHashMap<String, Object>();
              v.put("id", r.getObject("id"));
              v.put("aftersaleId", r.getObject("aftersales_case_id"));
              v.put("purchaseOrderId", r.getObject("supplier_purchase_order_id"));
              v.put("direction", r.getString("direction"));
              v.put("amountFen", r.getLong("amount_fen"));
              v.put("currency", r.getString("currency"));
              v.put("state", r.getString("state"));
              v.put("externalReference", r.getString("external_reference"));
              v.put("verifiedAt", instant(r, "verified_at"));
              v.put("version", r.getLong("version"));
              v.put("createdAt", instant(r, "created_at"));
              return v;
            },
            p.currentTenantId(),
            id));
    return m;
  }

  @Transactional(readOnly = true)
  public M1Support.Page<Map<String, Object>> list(
      TenantPrincipal p,
      int limit,
      int offset,
      UUID store,
      UUID order,
      String state,
      String type,
      String source,
      String upstream) {
    stores.requireDemo();
    M1Support.page(limit, offset);
    if (state != null
            && !Set.of(
                    "REQUESTED",
                    "UNDER_REVIEW",
                    "SUPPLIER_COORDINATION",
                    "RETURN_IN_TRANSIT",
                    "REFUND_PENDING",
                    "RESOLVED",
                    "REJECTED",
                    "MANUAL_REVIEW")
                .contains(state)
        || type != null && !Arrays.stream(Type.values()).anyMatch(v -> v.name().equals(type))
        || source != null
            && !Arrays.stream(SourceState.values()).anyMatch(v -> v.name().equals(source))
        || upstream != null
            && !Arrays.stream(Upstream.values()).anyMatch(v -> v.name().equals(upstream)))
      throw M1Support.invalid();
    if (store != null) stores.get(p, store);
    var args = new ArrayList<Object>();
    String where = support.scope(p, "s", args);
    Object[][] filters = {
      {"c.store_id", store},
      {"c.sales_order_id", order},
      {"c.state", state},
      {"c.type", type},
      {"c.source_state", source},
      {"c.upstream_state", upstream}
    };
    for (var f : filters)
      if (f[1] != null) {
        where += " and " + f[0] + "=?";
        args.add(f[1]);
      }
    String sql =
        " from aftersales_cases c join stores s on s.tenant_id=c.tenant_id and s.id=c.store_id"
            + " where "
            + where;
    long count = jdbc.queryForObject("select count(*)" + sql, Long.class, args.toArray());
    args.add(limit);
    args.add(offset);
    return new M1Support.Page<>(
        jdbc.query(
            "select c.*" + sql + " order by c.updated_at desc,c.id desc limit ? offset ?",
            (r, n) -> row(r),
            args.toArray()),
        count,
        limit,
        offset);
  }

  @Transactional
  public Map<String, Object> update(TenantPrincipal p, UUID id, String key, Update u) {
    var c = detail(p, id);
    return support.command(
        p.currentTenantId(),
        key,
        "M4_AFTERSALE_ACTION:" + id,
        u,
        Map.class,
        () -> {
          String state = (String) c.get("state");
          if (Set.of("RESOLVED", "REJECTED").contains(state))
            throw M1Support.conflict("AFTERSALE_TERMINAL");
          String next =
              switch (u.action()) {
                case ACKNOWLEDGE -> "UNDER_REVIEW";
                case RETURN_SENT -> "RETURN_IN_TRANSIT";
                case REQUEST_SUPPLIER_CANCEL, REQUEST_SUPPLIER_REFUND -> "SUPPLIER_COORDINATION";
              };
          if (u.action() == Action.RETURN_SENT && !c.get("type").equals("RETURN_REFUND"))
            throw M1Support.conflict("RETURN_NOT_REQUIRED");
          int n =
              jdbc.update(
                  "update aftersales_cases set state=?,version=version+1,updated_at=now() where"
                      + " tenant_id=? and id=? and version=?",
                  next,
                  p.currentTenantId(),
                  id,
                  u.version());
          if (n != 1) throw M1Support.conflict("VERSION_CONFLICT");
          if (u.action() == Action.REQUEST_SUPPLIER_CANCEL
              || u.action() == Action.REQUEST_SUPPLIER_REFUND) {
            String co =
                u.action() == Action.REQUEST_SUPPLIER_CANCEL
                    ? "CANCEL_REQUESTED"
                    : "REFUND_PENDING";
            jdbc.update(
                "update aftersales_coordinations set state=?,updated_at=now(),version=version+1"
                    + " where tenant_id=? and aftersales_case_id=? and state not in"
                    + " ('QUERY_PAYMENT','UNKNOWN','REFUNDED','CANCELLED')",
                co,
                p.currentTenantId(),
                id);
          }
          jdbc.update(
              "update aftersales_cases set upstream_state=coalesce((select state from"
                  + " aftersales_coordinations where tenant_id=? and aftersales_case_id=? order by"
                  + " id limit 1),upstream_state) where tenant_id=? and id=?",
              p.currentTenantId(),
              id,
              p.currentTenantId(),
              id);
          activity.record(
              p.currentTenantId(),
              (UUID) c.get("salesOrderId"),
              p.userId(),
              "AFTERSALES",
              "MOCK_AFTERSALE_" + u.action(),
              "AFTERSALE",
              id,
              null,
              "case-action:" + id + ":" + u.version(),
              true);
          return detail(p, id);
        });
  }

  @Transactional
  public Map<String, Object> reconcile(TenantPrincipal p, UUID id, String key) {
    var c = detail(p, id);
    return support.command(
        p.currentTenantId(),
        key,
        "M4_AFTERSALE_RECONCILE:" + id,
        Map.of("id", id),
        Map.class,
        () -> {
          UUID job = UUID.randomUUID();
          jdbc.update(
              "insert into outbox_events(id,tenant_id,aggregate_id,event_type,payload)"
                  + " values(?,?,?,'M4_AFTERSALE_RECONCILE','{}')",
              job,
              p.currentTenantId(),
              id);
          activity.record(
              p.currentTenantId(),
              (UUID) c.get("salesOrderId"),
              p.userId(),
              "AFTERSALES",
              "MOCK_AFTERSALE_QUERY_REQUESTED",
              "AFTERSALE",
              id,
              null,
              "case-reconcile:" + key,
              false);
          return detail(p, id);
        });
  }
}
