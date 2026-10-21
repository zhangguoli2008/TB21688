package com.smartprocure.logistics;

import com.smartprocure.common.*;
import com.smartprocure.provider.*;
import com.smartprocure.store.StoreService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ShipmentWorker {
  private record Job(
      UUID id,
      UUID tenant,
      UUID aggregate,
      String type,
      String external,
      int attempts,
      UUID lease) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ShippingProviderAdapter provider;
  private final ShipmentService service;
  private final M1Support support;
  private final StoreService stores;
  private final M4Activity activity;

  public ShipmentWorker(
      JdbcTemplate j,
      org.springframework.transaction.PlatformTransactionManager tm,
      ShippingProviderAdapter p,
      ShipmentService s,
      M1Support su,
      StoreService st,
      M4Activity a) {
    jdbc = j;
    tx = new TransactionTemplate(tm);
    provider = p;
    service = s;
    support = su;
    stores = st;
    activity = a;
  }

  public void runOnce() {
    stores.requireDemo();
    for (int n = 0; n < 40; n++) {
      var j = tx.execute(s -> claim());
      if (j == null) return;
      try {
        if (j.type().equals("M4_SHIPMENT_SOURCE")) {
          var source = provider.source(j.tenant(), j.aggregate(), j.external());
          tx.executeWithoutResult(s -> applySource(j, source));
        } else {
          var request = tx.execute(s -> prepare(j));
          if (request == null) continue;
          var ack = provider.query(j.tenant(), request);
          if (ack.isEmpty()) {
            if (jdbc.queryForObject(
                    "select tracking_version from shipments where tenant_id=? and id=?",
                    Long.class,
                    j.tenant(),
                    request.shipmentId())
                != request.trackingVersion()) throw M1Support.conflict("STALE_SHIPMENT_VERSION");
            provider.submit(j.tenant(), request);
            ack = provider.query(j.tenant(), request);
          }
          var result = ack;
          tx.executeWithoutResult(s -> applyAck(j, request, result));
        }
      } catch (RuntimeException e) {
        tx.executeWithoutResult(s -> failure(j, e));
      }
    }
  }

  private Job claim() {
    var rows =
        jdbc.query(
            "select id,tenant_id,aggregate_id,event_type,payload->>'externalShipmentId',attempts"
                + " from outbox_events where event_type in"
                + " ('M4_SHIPMENT_SOURCE','M4_SHIPMENT_SYNC') and ((state in ('NEW','RETRY_WAIT')"
                + " and available_at<=now()) or(state='PROCESSING' and lease_until<now())) order by"
                + " available_at,id for update skip locked limit 1",
            (r, n) ->
                new Job(
                    r.getObject(1, UUID.class),
                    r.getObject(2, UUID.class),
                    r.getObject(3, UUID.class),
                    r.getString(4),
                    r.getString(5),
                    r.getInt(6) + 1,
                    UUID.randomUUID()));
    if (rows.isEmpty()) return null;
    var j = rows.getFirst();
    if (j.attempts() > 5) {
      jdbc.update(
          "update outbox_events set"
              + " state='DEAD',lease_until=null,lease_token=null,last_error_code='WORKER_LEASE_EXHAUSTED'"
              + " where tenant_id=? and id=?",
          j.tenant(),
          j.id());
      return null;
    }
    jdbc.update(
        "update outbox_events set"
            + " state='PROCESSING',attempts=?,lease_token=?,lease_until=now()+interval '30 seconds'"
            + " where tenant_id=? and id=?",
        j.attempts(),
        j.lease(),
        j.tenant(),
        j.id());
    return j;
  }

  private boolean owns(Job j) {
    return !jdbc.query(
            "select id from outbox_events where tenant_id=? and id=? and state='PROCESSING' and"
                + " lease_token=? and lease_until>clock_timestamp() for update",
            (r, n) -> r.getObject(1),
            j.tenant(),
            j.id(),
            j.lease())
        .isEmpty();
  }

  private void done(Job j) {
    jdbc.update(
        "update outbox_events set"
            + " state='DONE',published_at=now(),lease_token=null,lease_until=null,last_error_code=null"
            + " where tenant_id=? and id=? and lease_token=?",
        j.tenant(),
        j.id(),
        j.lease());
  }

  private void applySource(Job j, ShippingProviderAdapter.Source s) {
    if (!owns(j)) return;
    var p = service.purchase(j.tenant(), j.aggregate());
    stores.requireActive(j.tenant(), p.store(), true);
    jdbc.query(
        "select id from purchase_orders where tenant_id=? and id=? for update",
        (r, n) -> r.getObject(1),
        j.tenant(),
        j.aggregate());
    if (!Set.of("PAID", "FULFILLING", "COMPLETED").contains(p.state()))
      throw M1Support.conflict("PURCHASE_NOT_PAID");
    var old =
        jdbc.query(
            "select * from shipments where tenant_id=? and purchase_order_id=? and"
                + " external_shipment_id=? for update",
            (r, n) -> service.row(r),
            j.tenant(),
            j.aggregate(),
            j.external());
    UUID id = old.isEmpty() ? UUID.randomUUID() : (UUID) old.getFirst().get("id");
    if (!old.isEmpty() && (long) old.getFirst().get("sourceVersion") >= s.sourceVersion()) {
      done(j);
      return;
    }
    if (!old.isEmpty() && (int) old.getFirst().get("qty") != s.qty())
      throw M1Support.conflict("SHIPMENT_QUANTITY_IMMUTABLE");
    int allocated =
        jdbc.queryForObject(
            "select coalesce(sum(qty),0) from shipments where tenant_id=? and purchase_order_id=?"
                + " and id<>?",
            Integer.class,
            j.tenant(),
            j.aggregate(),
            id);
    if (s.qty() < 1 || allocated + s.qty() > p.qty())
      throw M1Support.conflict("SHIPMENT_QUANTITY_EXCEEDED");
    if (!old.isEmpty()
        && old.getFirst().get("carrierCode").equals(s.carrierCode())
        && old.getFirst().get("trackingNumber").equals(s.trackingNumber())) {
      jdbc.update(
          "update shipments set"
              + " state=?,source_state=?,source_version=?,source_digest=?,updated_at=now(),version=version+1"
              + " where tenant_id=? and id=?",
          s.sourceState(),
          s.sourceState(),
          s.sourceVersion(),
          M1Support.digest(support.json(s)),
          j.tenant(),
          id);
      done(j);
      activity.record(
          j.tenant(),
          p.order(),
          null,
          "LOGISTICS",
          "MOCK_SHIPMENT_SOURCE_VERIFIED",
          "SHIPMENT",
          id,
          null,
          "shipment-source:" + id + ":" + s.sourceVersion(),
          true);
      return;
    }
    long tracking = old.isEmpty() ? 1 : (long) old.getFirst().get("trackingVersion") + 1;
    String digest = M1Support.digest(support.json(s));
    jdbc.update(
        "insert into"
            + " shipments(id,tenant_id,purchase_order_id,external_shipment_id,carrier_code,tracking_number,state,shipped_at,sales_order_id,sales_order_item_id,store_id,qty,source_state,source_version,source_digest,tracking_version)"
            + " values(?,?,?,?,?,?,?,now(),?,?,?,?,?,?,?,?) on"
            + " conflict(tenant_id,purchase_order_id,external_shipment_id) do update set"
            + " carrier_code=excluded.carrier_code,tracking_number=excluded.tracking_number,state=excluded.state,source_state=excluded.source_state,source_version=excluded.source_version,source_digest=excluded.source_digest,tracking_version=excluded.tracking_version,updated_at=now(),version=shipments.version+1",
        id,
        j.tenant(),
        j.aggregate(),
        j.external(),
        s.carrierCode(),
        s.trackingNumber(),
        s.sourceState(),
        p.order(),
        p.item(),
        p.store(),
        s.qty(),
        s.sourceState(),
        s.sourceVersion(),
        digest,
        tracking);
    jdbc.update(
        "insert into"
            + " shipment_versions(tenant_id,shipment_id,tracking_version,carrier_code,tracking_number,source_state,source_version,qty)"
            + " values(?,?,?,?,?,?,?,?)",
        j.tenant(),
        id,
        tracking,
        s.carrierCode(),
        s.trackingNumber(),
        s.sourceState(),
        s.sourceVersion(),
        s.qty());
    jdbc.update(
        "insert into mock_shipment_configs(tenant_id,shipment_id,behavior) values(?,?,?) on"
            + " conflict(tenant_id,shipment_id) do update set"
            + " behavior=excluded.behavior,version=mock_shipment_configs.version+1",
        j.tenant(),
        id,
        s.behavior());
    UUID sync = UUID.randomUUID();
    String key = "MOCK-SHIP-" + id + "-" + tracking;
    jdbc.update(
        "insert into"
            + " shipment_syncs(id,tenant_id,shipment_id,sales_order_id,sales_order_item_id,qty,tracking_version,provider_key)"
            + " values(?,?,?,?,?,?,?,?)",
        sync,
        j.tenant(),
        id,
        p.order(),
        p.item(),
        s.qty(),
        tracking,
        key);
    jdbc.update(
        "insert into outbox_events(tenant_id,aggregate_id,event_type,payload)"
            + " values(?,?,'M4_SHIPMENT_SYNC','{}')",
        j.tenant(),
        sync);
    done(j);
    activity.record(
        j.tenant(),
        p.order(),
        null,
        "LOGISTICS",
        "MOCK_SHIPMENT_SOURCE_VERIFIED",
        "SHIPMENT",
        id,
        null,
        "shipment-source:" + id + ":" + s.sourceVersion(),
        true);
  }

  private ShippingProviderAdapter.Request prepare(Job j) {
    if (!owns(j)) return null;
    var rows =
        jdbc.query(
            "select ss.*,v.carrier_code,v.tracking_number,s.tracking_version"
                + " current_version,s.store_id from shipment_syncs ss join shipments s on"
                + " s.tenant_id=ss.tenant_id and s.id=ss.shipment_id join shipment_versions v on"
                + " v.tenant_id=ss.tenant_id and v.shipment_id=ss.shipment_id and"
                + " v.tracking_version=ss.tracking_version where ss.tenant_id=? and ss.id=? for"
                + " update of ss,s",
            (r, n) ->
                new Object[] {
                  new ShippingProviderAdapter.Request(
                      r.getObject("shipment_id", UUID.class),
                      r.getObject("sales_order_id", UUID.class),
                      r.getObject("sales_order_item_id", UUID.class),
                      r.getInt("qty"),
                      r.getLong("tracking_version"),
                      r.getString("carrier_code"),
                      r.getString("tracking_number"),
                      r.getString("provider_key")),
                  r.getLong("current_version"),
                  r.getString("state"),
                  r.getObject("store_id", UUID.class)
                },
            j.tenant(),
            j.aggregate());
    if (rows.isEmpty()) throw M1Support.missing();
    var row = rows.getFirst();
    var r = (ShippingProviderAdapter.Request) row[0];
    stores.requireActive(j.tenant(), (UUID) row[3], true);
    if (row[2].equals("ACKED")) {
      done(j);
      return null;
    }
    boolean blocked =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "select exists(select 1 from sales_order_items i join sales_orders o on"
                    + " o.tenant_id=i.tenant_id and o.id=i.sales_order_id where i.tenant_id=? and"
                    + " i.id=? and (o.source_status<>'PAID' or i.aftersale_hold_qty>0 or"
                    + " exists(select 1 from aftersales_cases c where c.tenant_id=i.tenant_id and"
                    + " c.sales_order_item_id=i.id and c.state not in ('RESOLVED','REJECTED'))))",
                Boolean.class,
                j.tenant(),
                r.salesOrderItemId()));
    if (blocked && r.trackingVersion() == (long) row[1])
      throw M1Support.conflict("SALES_FULFILLMENT_BLOCKED");
    jdbc.update(
        "update shipment_syncs set"
            + " state='SENDING',needs_query=true,version=version+1,updated_at=now() where"
            + " tenant_id=? and id=?",
        j.tenant(),
        j.aggregate());
    return r;
  }

  private void applyAck(
      Job j,
      ShippingProviderAdapter.Request request,
      Optional<ShippingProviderAdapter.Ack> result) {
    if (!owns(j)) return;
    if (result.isEmpty()
        || !request.equals(result.get().request())
        || result.get().platformAck() == null)
      throw M1Support.conflict("SHIPMENT_AUTHORITY_MISMATCH");
    jdbc.query(
        "select id from sales_order_items where tenant_id=? and id=? for no key update",
        (rs, n) -> rs.getObject(1),
        j.tenant(),
        request.salesOrderItemId());
    var current =
        jdbc.queryForObject(
            "select tracking_version from shipments where tenant_id=? and id=? for update",
            Long.class,
            j.tenant(),
            request.shipmentId());

    jdbc.update(
        "update shipment_syncs set"
            + " state='ACKED',platform_ack=?,last_error_code=null,needs_query=false,acknowledged_at=now(),updated_at=now(),version=version+1"
            + " where tenant_id=? and id=?",
        result.get().platformAck(),
        j.tenant(),
        j.aggregate());
    jdbc.update(
        "update shipments set acknowledged=true,updated_at=now(),version=version+1 where"
            + " tenant_id=? and id=?",
        j.tenant(),
        request.shipmentId());
    jdbc.update(
        "update sales_order_items set shipment_ack_qty=(select coalesce(sum(qty),0) from shipments"
            + " where tenant_id=? and sales_order_item_id=? and acknowledged),version=version+1"
            + " where tenant_id=? and id=?",
        j.tenant(),
        request.salesOrderItemId(),
        j.tenant(),
        request.salesOrderItemId());
    done(j);
    activity.record(
        j.tenant(),
        request.salesOrderId(),
        null,
        "LOGISTICS",
        "MOCK_SHIPMENT_ACKED",
        "SHIPMENT",
        request.shipmentId(),
        null,
        "shipment-ack:" + request.shipmentId() + ":" + request.trackingVersion(),
        true);
  }

  private void failure(Job j, RuntimeException ex) {
    if (!owns(j)) return;
    String code = ex instanceof ApiException a ? a.code() : "SHIPMENT_TEMPORARILY_UNAVAILABLE";
    boolean blocked =
        Set.of(
                    "SHIPMENT_CORRECTION_BLOCKED",
                    "SHIPMENT_QUANTITY_EXCEEDED",
                    "SHIPMENT_QUANTITY_IMMUTABLE",
                    "PURCHASE_NOT_PAID",
                    "AUTHORIZATION_UNAVAILABLE",
                    "CAPABILITY_UNAVAILABLE",
                    "SHIPMENT_AUTHORITY_MISMATCH",
                    "SALES_FULFILLMENT_BLOCKED",
                    "STALE_SHIPMENT_VERSION")
                .contains(code)
            || j.attempts() >= 5;
    String state = blocked ? "BLOCKED" : "RETRY_WAIT";
    jdbc.update(
        "update outbox_events set state=?,available_at=now()+(?*interval '1"
            + " second'),lease_token=null,lease_until=null,last_error_code=? where tenant_id=? and"
            + " id=? and lease_token=?",
        blocked && j.attempts() >= 5 ? "DEAD" : state,
        Math.min(60, 1 << j.attempts()),
        code,
        j.tenant(),
        j.id(),
        j.lease());
    if (j.type().equals("M4_SHIPMENT_SYNC")) {
      jdbc.update(
          "update shipment_syncs set"
              + " state=?,last_error_code=?,retry_count=retry_count+1,needs_query=true,updated_at=now(),version=version+1"
              + " where tenant_id=? and id=?",
          state,
          code,
          j.tenant(),
          j.aggregate());
      var context =
          jdbc.query(
                  "select shipment_id,sales_order_id,tracking_version from shipment_syncs where"
                      + " tenant_id=? and id=?",
                  (r, n) ->
                      new Object[] {
                        r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3)
                      },
                  j.tenant(),
                  j.aggregate())
              .getFirst();
      activity.record(
          j.tenant(),
          (UUID) context[1],
          null,
          "LOGISTICS",
          "MOCK_SHIPMENT_SYNC_" + state,
          "SHIPMENT",
          (UUID) context[0],
          code,
          "shipment-failure:" + context[0] + ":" + context[2] + ":" + state,
          true);
    }
  }
}
