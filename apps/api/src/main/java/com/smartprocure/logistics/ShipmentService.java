package com.smartprocure.logistics;

import com.smartprocure.common.*;
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
public class ShipmentService {
  public enum State {
    SHIPPED,
    DELIVERED,
    EXCEPTION
  }

  public enum Behavior {
    NORMAL,
    SUBMIT_5XX,
    SUBMIT_TIMEOUT_AFTER,
    QUERY_TIMEOUT,
    AUTHORITY_MISMATCH,
    CORRECTION_BLOCKED
  }

  public record Event(
      @NotBlank @Pattern(regexp = "MOCK-[A-Za-z0-9_-]{1,100}") String eventId,
      @NotBlank @Pattern(regexp = "MOCK-PKG-[A-Za-z0-9_-]{1,100}") String externalShipmentId,
      @Min(1) long sourceVersion,
      @Min(1) @Max(10000) int qty,
      @NotNull @Pattern(regexp = "MOCK_EXPRESS") String carrierCode,
      @NotBlank @Pattern(regexp = "MOCK-TRACK-[A-Za-z0-9_-]{1,100}") String trackingNumber,
      @NotNull State sourceState,
      @NotNull Behavior behavior) {}

  public record ConfigUpdate(@NotNull Behavior behavior, @NotNull @Min(0) Long version) {}

  public record Accepted(String eventId, UUID jobId, String state, String mode) {}

  private final JdbcTemplate jdbc;
  private final M1Support support;
  private final StoreService stores;
  private final M4Activity activity;

  public ShipmentService(JdbcTemplate j, M1Support s, StoreService st, M4Activity a) {
    jdbc = j;
    support = s;
    stores = st;
    activity = a;
  }

  public record Purchase(UUID id, UUID store, UUID order, UUID item, int qty, String state) {}

  public Purchase purchase(UUID t, UUID p) {
    return jdbc
        .query(
            "select p.id,o.store_id,o.id order_id,si.id item_id,i.qty,p.state from purchase_orders"
                + " p join purchase_intents i on i.tenant_id=p.tenant_id and"
                + " i.id=p.purchase_intent_id join sales_order_items si on si.tenant_id=i.tenant_id"
                + " and si.id=i.sales_order_item_id join sales_orders o on o.tenant_id=si.tenant_id"
                + " and o.id=si.sales_order_id where p.tenant_id=? and p.id=?",
            (r, n) ->
                new Purchase(
                    p,
                    r.getObject(2, UUID.class),
                    r.getObject(3, UUID.class),
                    r.getObject(4, UUID.class),
                    r.getInt(5),
                    r.getString(6)),
            t,
            p)
        .stream()
        .findFirst()
        .orElseThrow(M1Support::missing);
  }

  @Transactional
  public Accepted event(TenantPrincipal p, UUID purchase, String key, Event e) {
    var po = purchase(p.currentTenantId(), purchase);
    stores.get(p, po.store());
    stores.requireActive(p.currentTenantId(), po.store(), true);
    return support.command(
        p.currentTenantId(),
        key,
        "M4_SHIPMENT_EVENT:" + purchase,
        e,
        Accepted.class,
        () -> {
          if (!Set.of("PAID", "FULFILLING", "COMPLETED").contains(po.state()))
            throw M1Support.conflict("PURCHASE_NOT_PAID");
          String digest = M1Support.digest(support.json(e));
          UUID job = UUID.randomUUID();
          var duplicate = duplicate(p.currentTenantId(), e.eventId(), digest, "SHIPMENT", purchase);
          if (duplicate != null) return duplicate;
          var previous =
              jdbc.query(
                  "select"
                      + " snapshot->>'trackingNumber',snapshot->>'carrierCode',snapshot->>'sourceState',(snapshot->>'qty')::int,source_version"
                      + " from mock_shipment_authority where tenant_id=? and purchase_order_id=?"
                      + " and external_shipment_id=? for update",
                  (r, n) ->
                      new Object[] {
                        r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getLong(5)
                      },
                  p.currentTenantId(),
                  purchase,
                  e.externalShipmentId());
          if (!previous.isEmpty()
              && (long) previous.getFirst()[4] == e.sourceVersion()
              && (!previous.getFirst()[0].equals(e.trackingNumber())
                  || !previous.getFirst()[1].equals(e.carrierCode())
                  || !previous.getFirst()[2].equals(e.sourceState().name())
                  || (int) previous.getFirst()[3] != e.qty()))
            throw M1Support.conflict("AUTHORITY_VERSION_CONFLICT");
          var source =
              new ShippingProviderAdapter.Source(
                  e.eventId(),
                  e.externalShipmentId(),
                  e.sourceVersion(),
                  e.qty(),
                  e.carrierCode(),
                  e.trackingNumber(),
                  e.sourceState().name(),
                  e.behavior().name());
          jdbc.update(
              "insert into"
                  + " mock_shipment_authority(tenant_id,purchase_order_id,external_shipment_id,source_version,snapshot)"
                  + " values(?,?,?,?,?::jsonb) on"
                  + " conflict(tenant_id,purchase_order_id,external_shipment_id) do update set"
                  + " source_version=excluded.source_version,snapshot=excluded.snapshot where"
                  + " mock_shipment_authority.source_version<excluded.source_version",
              p.currentTenantId(),
              purchase,
              e.externalShipmentId(),
              e.sourceVersion(),
              support.json(source));
          jdbc.update(
              "insert into m4_mock_events(tenant_id,event_id,event_type,aggregate_id,digest,job_id)"
                  + " values(?,?,'SHIPMENT',?,?,?)",
              p.currentTenantId(),
              e.eventId(),
              purchase,
              digest,
              job);
          jdbc.update(
              "insert into outbox_events(id,tenant_id,aggregate_id,event_type,payload)"
                  + " values(?,?,?,'M4_SHIPMENT_SOURCE',?::jsonb)",
              job,
              p.currentTenantId(),
              purchase,
              support.json(
                  Map.of("externalShipmentId", e.externalShipmentId(), "eventId", e.eventId())));
          activity.record(
              p.currentTenantId(),
              po.order(),
              p.userId(),
              "LOGISTICS",
              "MOCK_SHIPMENT_EVENT_ACCEPTED",
              "PURCHASE_ORDER",
              purchase,
              null,
              e.eventId(),
              false);
          return new Accepted(e.eventId(), job, "QUEUED", "MOCK");
        });
  }

  public Accepted duplicate(UUID t, String event, String digest, String kind, UUID aggregate) {
    var rows =
        jdbc.query(
            "select digest,job_id,event_type,aggregate_id from m4_mock_events where tenant_id=? and"
                + " event_id=?",
            (r, n) ->
                new Object[] {
                  r.getString(1),
                  r.getObject(2, UUID.class),
                  r.getString(3),
                  r.getObject(4, UUID.class)
                },
            t,
            event);
    if (rows.isEmpty()) return null;
    if (!digest.equals(rows.getFirst()[0])
        || !kind.equals(rows.getFirst()[2])
        || !aggregate.equals(rows.getFirst()[3])) throw M1Support.conflict("EVENT_CONFLICT");
    return new Accepted(event, (UUID) rows.getFirst()[1], "QUEUED", "MOCK");
  }

  static Instant instant(ResultSet r, String name) throws SQLException {
    var t = r.getTimestamp(name);
    return t == null ? null : t.toInstant();
  }

  public Map<String, Object> row(ResultSet r) throws SQLException {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", r.getObject("id"));
    m.put("purchaseOrderId", r.getObject("purchase_order_id"));
    m.put("salesOrderId", r.getObject("sales_order_id"));
    m.put("salesOrderItemId", r.getObject("sales_order_item_id"));
    m.put("storeId", r.getObject("store_id"));
    m.put("externalShipmentId", r.getString("external_shipment_id"));
    m.put("carrierCode", r.getString("carrier_code"));
    m.put("trackingNumber", r.getString("tracking_number"));
    m.put("qty", r.getInt("qty"));
    m.put("state", r.getString("state"));
    m.put("sourceState", r.getString("source_state"));
    m.put("sourceVersion", r.getLong("source_version"));
    m.put("trackingVersion", r.getLong("tracking_version"));
    m.put("version", r.getLong("version"));
    m.put("shippedAt", instant(r, "shipped_at"));
    m.put("updatedAt", instant(r, "updated_at"));
    m.put("currentSync", null);
    m.put("mode", "MOCK");
    return m;
  }

  public Map<String, Object> sync(ResultSet r) throws SQLException {
    var m = new LinkedHashMap<String, Object>();
    for (var k : List.of("id", "shipment_id", "sales_order_id", "sales_order_item_id"))
      m.put(
          switch (k) {
            case "shipment_id" -> "shipmentId";
            case "sales_order_id" -> "salesOrderId";
            case "sales_order_item_id" -> "salesOrderItemId";
            default -> k;
          },
          r.getObject(k));
    m.put("qty", r.getInt("qty"));
    m.put("trackingVersion", r.getLong("tracking_version"));
    m.put("state", r.getString("state"));
    m.put("platformAck", r.getString("platform_ack"));
    m.put("lastErrorCode", r.getString("last_error_code"));
    m.put("retryCount", r.getInt("retry_count"));
    m.put("needsQuery", r.getBoolean("needs_query"));
    m.put("acknowledgedAt", instant(r, "acknowledged_at"));
    m.put("updatedAt", instant(r, "updated_at"));
    return m;
  }

  private Map<String, Object> base(UUID t, UUID id) {
    var m =
        jdbc
            .query("select * from shipments where tenant_id=? and id=?", (r, n) -> row(r), t, id)
            .stream()
            .findFirst()
            .orElseThrow(M1Support::missing);
    m.put(
        "currentSync",
        jdbc
            .query(
                "select * from shipment_syncs where tenant_id=? and shipment_id=? and"
                    + " tracking_version=?",
                (r, n) -> sync(r),
                t,
                id,
                m.get("trackingVersion"))
            .stream()
            .findFirst()
            .orElse(null));
    return m;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(TenantPrincipal p, UUID id) {
    stores.requireDemo();
    var m = base(p.currentTenantId(), id);
    stores.get(p, (UUID) m.get("storeId"));
    m.put(
        "versions",
        jdbc.query(
            "select * from shipment_versions where tenant_id=? and shipment_id=? order by"
                + " tracking_version",
            (r, n) ->
                Map.of(
                    "trackingVersion",
                    r.getLong("tracking_version"),
                    "carrierCode",
                    r.getString("carrier_code"),
                    "trackingNumber",
                    r.getString("tracking_number"),
                    "sourceState",
                    r.getString("source_state"),
                    "sourceVersion",
                    r.getLong("source_version"),
                    "qty",
                    r.getInt("qty"),
                    "observedAt",
                    instant(r, "observed_at")),
            p.currentTenantId(),
            id));
    m.put(
        "syncs",
        jdbc.query(
            "select * from shipment_syncs where tenant_id=? and shipment_id=? order by"
                + " tracking_version",
            (r, n) -> sync(r),
            p.currentTenantId(),
            id));
    m.put(
        "config",
        jdbc.queryForObject(
            "select behavior,version from mock_shipment_configs where tenant_id=? and"
                + " shipment_id=?",
            (r, n) -> Map.of("behavior", r.getString(1), "version", r.getLong(2), "mode", "MOCK"),
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
      UUID purchase,
      UUID order,
      String state,
      String syncState) {
    stores.requireDemo();
    M1Support.page(limit, offset);
    if (state != null
            && !Set.of("NOT_CREATED", "LABEL_CREATED", "SHIPPED", "DELIVERED", "LOST", "EXCEPTION")
                .contains(state)
        || syncState != null
            && !Set.of("NOT_SENT", "SENDING", "ACKED", "RETRY_WAIT", "BLOCKED").contains(syncState))
      throw M1Support.invalid();
    if (store != null) stores.get(p, store);
    var args = new ArrayList<Object>();
    String where = support.scope(p, "st", args);
    for (var entry :
        Map.of(
                "s.store_id",
                store == null ? "" : store,
                "s.purchase_order_id",
                purchase == null ? "" : purchase,
                "s.sales_order_id",
                order == null ? "" : order,
                "s.state",
                state == null ? "" : state,
                "ss.state",
                syncState == null ? "" : syncState)
            .entrySet())
      if (!entry.getValue().equals("")) {
        where += " and " + entry.getKey() + "=?";
        args.add(entry.getValue());
      }
    String sql =
        " from shipments s join stores st on st.tenant_id=s.tenant_id and st.id=s.store_id left"
            + " join shipment_syncs ss on ss.tenant_id=s.tenant_id and ss.shipment_id=s.id and"
            + " ss.tracking_version=s.tracking_version where "
            + where;
    long count = jdbc.queryForObject("select count(*)" + sql, Long.class, args.toArray());
    args.add(limit);
    args.add(offset);
    var ids =
        jdbc.query(
            "select s.id" + sql + " order by s.updated_at desc,s.id desc limit ? offset ?",
            (r, n) -> r.getObject(1, UUID.class),
            args.toArray());
    return new M1Support.Page<>(
        ids.stream().map(id -> base(p.currentTenantId(), id)).toList(), count, limit, offset);
  }

  @Transactional
  public Map<String, Object> configure(TenantPrincipal p, UUID id, String key, ConfigUpdate u) {
    var m = detail(p, id);
    return support.command(
        p.currentTenantId(),
        key,
        "M4_SHIPMENT_CONFIG:" + id,
        u,
        Map.class,
        () -> {
          int n =
              jdbc.update(
                  "update mock_shipment_configs set behavior=?,version=version+1 where tenant_id=?"
                      + " and shipment_id=? and version=?",
                  u.behavior().name(),
                  p.currentTenantId(),
                  id,
                  u.version());
          if (n != 1) throw M1Support.conflict("VERSION_CONFLICT");
          activity.record(
              p.currentTenantId(),
              (UUID) m.get("salesOrderId"),
              p.userId(),
              "LOGISTICS",
              "MOCK_SHIPMENT_CONFIG_UPDATED",
              "SHIPMENT",
              id,
              null,
              "shipment-config:" + id + ":" + u.version(),
              false);
          return detail(p, id);
        });
  }

  @Transactional
  public Map<String, Object> retry(TenantPrincipal p, UUID id, String key) {
    var m = detail(p, id);
    return support.command(
        p.currentTenantId(),
        key,
        "M4_SHIPMENT_RETRY:" + id,
        Map.of("id", id),
        Map.class,
        () -> {
          UUID sync = (UUID) ((Map<?, ?>) m.get("currentSync")).get("id");
          jdbc.update(
              "update outbox_events set"
                  + " state='NEW',attempts=0,available_at=now(),lease_token=null,lease_until=null"
                  + " where tenant_id=? and aggregate_id=? and event_type='M4_SHIPMENT_SYNC' and"
                  + " state<>'PROCESSING'",
              p.currentTenantId(),
              sync);
          activity.record(
              p.currentTenantId(),
              (UUID) m.get("salesOrderId"),
              p.userId(),
              "LOGISTICS",
              "MOCK_SHIPMENT_RETRY_REQUESTED",
              "SHIPMENT",
              id,
              null,
              "shipment-retry:" + key,
              false);
          return detail(p, id);
        });
  }
}
