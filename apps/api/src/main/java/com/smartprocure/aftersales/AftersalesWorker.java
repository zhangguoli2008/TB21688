package com.smartprocure.aftersales;

import com.smartprocure.common.*;
import com.smartprocure.provider.*;
import com.smartprocure.store.StoreService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AftersalesWorker {
  private record Job(
      UUID id,
      UUID tenant,
      UUID aggregate,
      String type,
      String external,
      UUID purchase,
      int attempts,
      UUID lease) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final AftersalesProviderAdapter provider;
  private final AftersalesService service;
  private final StoreService stores;
  private final M4Activity activity;
  private final M1Support support;

  public AftersalesWorker(
      JdbcTemplate j,
      org.springframework.transaction.PlatformTransactionManager tm,
      AftersalesProviderAdapter p,
      AftersalesService s,
      StoreService st,
      M4Activity a,
      M1Support su) {
    jdbc = j;
    tx = new TransactionTemplate(tm);
    provider = p;
    service = s;
    stores = st;
    activity = a;
    support = su;
  }

  public void runOnce() {
    stores.requireDemo();
    for (int n = 0; n < 20; n++) {
      var j = tx.execute(s -> claim());
      if (j == null) return;
      try {
        if (j.type().equals("M4_AFTERSALE_SOURCE")) {
          var a = provider.source(j.tenant(), j.aggregate(), j.external());
          tx.executeWithoutResult(s -> source(j, a));
        } else if (j.type().equals("M4_AFTERSALE_RECONCILE")) {
          var c = service.base(j.tenant(), j.aggregate());
          var sourceSnapshot =
              provider.source(
                  j.tenant(), (UUID) c.get("salesOrderId"), (String) c.get("sourceCaseId"));
          var purchases =
              jdbc.query(
                  "select purchase_order_id from mock_supplier_aftersale_authority where"
                      + " tenant_id=? and aftersales_case_id=?",
                  (r, rowNumber) -> r.getObject(1, UUID.class),
                  j.tenant(),
                  j.aggregate());
          var snapshots =
              purchases.stream()
                  .map(id -> provider.supplier(j.tenant(), j.aggregate(), id))
                  .toList();
          tx.executeWithoutResult(s -> reconcileJob(j, sourceSnapshot, snapshots));
        } else {
          var a = provider.supplier(j.tenant(), j.aggregate(), j.purchase());
          tx.executeWithoutResult(s -> supplier(j, a));
        }
      } catch (RuntimeException ex) {
        tx.executeWithoutResult(s -> failure(j, ex));
      }
    }
  }

  private Job claim() {
    var rows =
        jdbc.query(
            "select"
                + " id,tenant_id,aggregate_id,event_type,payload->>'sourceCaseId',payload->>'purchaseOrderId',attempts"
                + " from outbox_events where event_type in"
                + " ('M4_AFTERSALE_SOURCE','M4_AFTERSALE_SUPPLIER','M4_AFTERSALE_RECONCILE') and"
                + " ((state in ('NEW','RETRY_WAIT') and available_at<=now())or(state='PROCESSING'"
                + " and lease_until<now())) order by available_at,id for update skip locked limit"
                + " 1",
            (r, n) ->
                new Job(
                    r.getObject(1, UUID.class),
                    r.getObject(2, UUID.class),
                    r.getObject(3, UUID.class),
                    r.getString(4),
                    r.getString(5),
                    r.getString(6) == null ? null : UUID.fromString(r.getString(6)),
                    r.getInt(7) + 1,
                    UUID.randomUUID()));
    if (rows.isEmpty()) return null;
    var j = rows.getFirst();
    if (j.attempts() > 5) {
      jdbc.update(
          "update outbox_events set"
              + " state='DEAD',last_error_code='WORKER_LEASE_EXHAUSTED',lease_token=null,lease_until=null"
              + " where tenant_id=? and id=?",
          j.tenant(),
          j.id());
      return null;
    }
    jdbc.update(
        "update outbox_events set state='PROCESSING',attempts=?,lease_until=now()+interval '30"
            + " seconds',lease_token=? where tenant_id=? and id=?",
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
            + " state='DONE',published_at=now(),last_error_code=null,lease_token=null,lease_until=null"
            + " where tenant_id=? and id=? and lease_token=?",
        j.tenant(),
        j.id(),
        j.lease());
  }

  private void source(Job j, AftersalesProviderAdapter.Source a) {
    applySource(j, a, true);
  }

  private void applySource(Job j, AftersalesProviderAdapter.Source a, boolean finish) {
    if (!owns(j)) return;
    UUID store =
        jdbc
            .query(
                "select store_id from sales_orders where tenant_id=? and id=?",
                (r, n) -> r.getObject(1, UUID.class),
                j.tenant(),
                j.aggregate())
            .stream()
            .findFirst()
            .orElseThrow(M1Support::missing);
    stores.requireActive(j.tenant(), store, true);
    var item =
        jdbc
            .query(
                "select o.store_id,i.qty,i.paid_allocated_fen from sales_order_items i join"
                    + " sales_orders o on o.tenant_id=i.tenant_id and o.id=i.sales_order_id where"
                    + " i.tenant_id=? and i.sales_order_id=? and i.id=? for no key update of i,o",
                (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getInt(2), r.getLong(3)},
                j.tenant(),
                j.aggregate(),
                a.salesOrderItemId())
            .stream()
            .findFirst()
            .orElseThrow(M1Support::missing);
    var old =
        jdbc.query(
            "select id,source_version,sales_order_item_id,type,qty,requested_refund_fen from"
                + " aftersales_cases where tenant_id=? and sales_order_id=? and source_case_id=?"
                + " for update",
            (r, n) ->
                new Object[] {
                  r.getObject(1, UUID.class),
                  r.getLong(2),
                  r.getObject(3, UUID.class),
                  r.getString(4),
                  r.getInt(5),
                  r.getLong(6)
                },
            j.tenant(),
            j.aggregate(),
            a.sourceCaseId());
    UUID id = old.isEmpty() ? UUID.randomUUID() : (UUID) old.getFirst()[0];
    if (!old.isEmpty() && (long) old.getFirst()[1] >= a.sourceVersion()) {
      if (finish) done(j);
      return;
    }
    if (!old.isEmpty()
        && (!old.getFirst()[2].equals(a.salesOrderItemId())
            || !old.getFirst()[3].equals(a.type())
            || (int) old.getFirst()[4] != a.qty()
            || (long) old.getFirst()[5] != a.requestedRefundFen()))
      throw M1Support.conflict("AFTERSALE_IDENTITY_CHANGED");
    if (a.qty() > (int) item[1]) throw M1Support.conflict("AFTERSALE_QUANTITY_EXCEEDED");
    String initial =
        a.sourceState().equals("REJECTED")
            ? "REJECTED"
            : a.sourceState().equals("ARBITRATION") ? "MANUAL_REVIEW" : "REQUESTED";
    jdbc.update(
        "insert into"
            + " aftersales_cases(id,tenant_id,sales_order_id,source_case_id,type,state,requested_refund_fen,store_id,sales_order_item_id,qty,currency,source_state,source_version,source_digest)"
            + " values(?,?,?,?,?,?,?,?,?,?,'CNY',?,?,?) on"
            + " conflict(tenant_id,sales_order_id,source_case_id) do update set"
            + " source_state=excluded.source_state,source_version=excluded.source_version,source_digest=excluded.source_digest,updated_at=now(),version=aftersales_cases.version+1",
        id,
        j.tenant(),
        j.aggregate(),
        a.sourceCaseId(),
        a.type(),
        initial,
        a.requestedRefundFen(),
        item[0],
        a.salesOrderItemId(),
        a.qty(),
        a.sourceState(),
        a.sourceVersion(),
        M1Support.digest(support.json(a)));
    var purchases =
        jdbc.query(
            "select po.id,po.total_fen,pi.qty,pa.state payment_state,po.state purchase_state from"
                + " purchase_orders po join purchase_intents pi on pi.tenant_id=po.tenant_id and"
                + " pi.id=po.purchase_intent_id left join payment_attempts pa on"
                + " pa.tenant_id=po.tenant_id and pa.purchase_order_id=po.id where po.tenant_id=?"
                + " and pi.sales_order_item_id=?",
            (r, n) ->
                new Object[] {
                  r.getObject(1, UUID.class),
                  r.getLong(2),
                  r.getInt(3),
                  r.getString(4),
                  r.getString(5)
                },
            j.tenant(),
            a.salesOrderItemId());
    for (var p : purchases) {
      String paid = (String) p[3], up;
      if (Set.of("PAYING", "UNKNOWN", "MANUAL_RECONCILIATION").contains(paid == null ? "" : paid))
        up = "QUERY_PAYMENT";
      else if ("PAID".equals(paid)) {
        boolean shipped =
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "select exists(select 1 from shipments where tenant_id=? and"
                        + " purchase_order_id=?)",
                    Boolean.class,
                    j.tenant(),
                    p[0]));
        up = shipped && a.type().equals("RETURN_REFUND") ? "RETURN_REQUIRED" : "PENDING";
      } else up = "PENDING";
      long amount =
          "PAID".equals(paid)
              ? java.math.BigInteger.valueOf((long) p[1])
                  .multiply(java.math.BigInteger.valueOf(Math.min(a.qty(), (int) p[2])))
                  .divide(java.math.BigInteger.valueOf((int) p[2]))
                  .longValueExact()
              : 0;
      jdbc.update(
          "insert into"
              + " aftersales_coordinations(tenant_id,aftersales_case_id,purchase_order_id,state,upstream_source_state,requested_refund_fen)"
              + " values(?,?,?,?,?,?) on conflict(tenant_id,aftersales_case_id,purchase_order_id)"
              + " do nothing",
          j.tenant(),
          id,
          p[0],
          up,
          up,
          amount);
    }
    if (a.sourceState().equals("REFUNDED"))
      refund(j.tenant(), id, null, "SALES", a.requestedRefundFen(), "MOCK-SALES-REFUND-" + id);
    if (a.sourceState().equals("REJECTED"))
      jdbc.update(
          "update aftersales_cases set state='REJECTED',updated_at=now(),version=version+1 where"
              + " tenant_id=? and id=?",
          j.tenant(),
          id);
    else reconcileCase(j.tenant(), id);
    service.refreshHold(j.tenant(), a.salesOrderItemId());
    if (finish) done(j);
    activity.record(
        j.tenant(),
        j.aggregate(),
        null,
        "AFTERSALES",
        "MOCK_AFTERSALE_AUTHORITY_VERIFIED",
        "AFTERSALE",
        id,
        null,
        "case-source:" + id + ":" + a.sourceVersion(),
        true);
  }

  private void refund(UUID t, UUID c, UUID p, String direction, long amount, String key) {
    if (amount == 0) return;
    long cap;
    if (direction.equals("SALES")) {
      cap =
          jdbc.queryForObject(
              "select i.paid_allocated_fen from aftersales_cases c join sales_order_items i on"
                  + " i.tenant_id=c.tenant_id and i.id=c.sales_order_item_id where c.tenant_id=?"
                  + " and c.id=?",
              Long.class,
              t,
              c);
      long used =
          jdbc.queryForObject(
              "select coalesce(sum(r.amount_fen),0) from refund_transactions r join"
                  + " aftersales_cases c on c.tenant_id=r.tenant_id and c.id=r.aftersales_case_id"
                  + " where r.tenant_id=? and c.sales_order_item_id=(select sales_order_item_id"
                  + " from aftersales_cases where tenant_id=? and id=?) and r.direction='SALES' and"
                  + " r.state='VERIFIED' and r.provider_key<>?",
              Long.class,
              t,
              t,
              c,
              key);
      if (amount > cap - used) throw M1Support.conflict("REFUND_AMOUNT_EXCEEDED");
    } else {
      cap =
          jdbc.queryForObject(
              "select coalesce(sum(amount_fen),0) from payment_attempts where tenant_id=? and"
                  + " purchase_order_id=? and state='PAID'",
              Long.class,
              t,
              p);
      long used =
          jdbc.queryForObject(
              "select coalesce(sum(amount_fen),0) from refund_transactions where tenant_id=? and"
                  + " supplier_purchase_order_id=? and direction='SUPPLIER' and state='VERIFIED'"
                  + " and provider_key<>?",
              Long.class,
              t,
              p,
              key);
      if (amount > cap - used) throw M1Support.conflict("REFUND_AMOUNT_EXCEEDED");
    }
    jdbc.update(
        "insert into"
            + " refund_transactions(tenant_id,aftersales_case_id,supplier_purchase_order_id,amount_fen,state,external_reference,direction,currency,verified_at,provider_key)"
            + " values(?,?,?,?,'VERIFIED',?,?,'CNY',now(),?) on conflict(tenant_id,provider_key) do"
            + " nothing",
        t,
        c,
        p,
        amount,
        key,
        direction,
        key);
  }

  private void supplier(Job j, AftersalesProviderAdapter.Supplier a) {
    applySupplier(j, a, true);
  }

  private void applySupplier(Job j, AftersalesProviderAdapter.Supplier a, boolean finish) {
    if (!owns(j)) return;
    var c = service.base(j.tenant(), j.aggregate());
    stores.requireActive(j.tenant(), (UUID) c.get("storeId"), true);
    jdbc.query(
        "select id from sales_order_items where tenant_id=? and id=? for no key update",
        (r, n) -> r.getObject(1),
        j.tenant(),
        c.get("salesOrderItemId"));
    var rows =
        jdbc.query(
            "select source_version,requested_refund_fen from aftersales_coordinations where"
                + " tenant_id=? and aftersales_case_id=? and purchase_order_id=? for update",
            (r, n) -> new long[] {r.getLong(1), r.getLong(2)},
            j.tenant(),
            j.aggregate(),
            a.purchaseOrderId());
    if (rows.isEmpty()) throw M1Support.missing();
    if (rows.getFirst()[0] >= a.sourceVersion()) {
      if (finish) done(j);
      return;
    }
    String payment =
        jdbc
            .query(
                "select state from payment_attempts where tenant_id=? and purchase_order_id=?",
                (r, n) -> r.getString(1),
                j.tenant(),
                a.purchaseOrderId())
            .stream()
            .findFirst()
            .orElse("UNPAID");
    if (Set.of("PAYING", "UNKNOWN", "MANUAL_RECONCILIATION").contains(payment)
        && Set.of("REFUNDED", "CANCELLED").contains(a.state()))
      throw M1Support.conflict("PAYMENT_STATUS_UNKNOWN");
    if (a.refundAmountFen() > rows.getFirst()[1])
      throw M1Support.conflict("REFUND_AMOUNT_EXCEEDED");
    if (a.state().equals("REFUNDED")) {
      if (!payment.equals("PAID")) throw M1Support.conflict("PURCHASE_NOT_PAID");
      long prior =
          jdbc.queryForObject(
              "select coalesce(sum(amount_fen),0) from refund_transactions where tenant_id=? and"
                  + " aftersales_case_id=? and supplier_purchase_order_id=? and"
                  + " direction='SUPPLIER' and state='VERIFIED'",
              Long.class,
              j.tenant(),
              j.aggregate(),
              a.purchaseOrderId());
      if (a.refundAmountFen() < prior) throw M1Support.conflict("REFUND_AUTHORITY_REGRESSION");
      refund(
          j.tenant(),
          j.aggregate(),
          a.purchaseOrderId(),
          "SUPPLIER",
          a.refundAmountFen() - prior,
          "MOCK-SUPPLIER-REFUND-"
              + j.aggregate()
              + "-"
              + a.purchaseOrderId()
              + "-"
              + a.sourceVersion());
    }
    jdbc.update(
        "update aftersales_coordinations set"
            + " state=?,upstream_source_state=?,source_version=?,refund_reference=?,cancellation_reference=?,updated_at=now(),version=version+1"
            + " where tenant_id=? and aftersales_case_id=? and purchase_order_id=?",
        a.state(),
        a.state(),
        a.sourceVersion(),
        a.state().equals("REFUNDED") ? "MOCK-REFUND-" + a.purchaseOrderId() : null,
        a.state().equals("CANCELLED") ? "MOCK-CANCEL-" + a.purchaseOrderId() : null,
        j.tenant(),
        j.aggregate(),
        a.purchaseOrderId());
    reconcileCase(j.tenant(), j.aggregate());
    if (finish) done(j);
    activity.record(
        j.tenant(),
        (UUID) c.get("salesOrderId"),
        null,
        "AFTERSALES",
        "MOCK_SUPPLIER_AFTERSALE_VERIFIED",
        "AFTERSALE",
        j.aggregate(),
        null,
        "case-supplier:" + j.aggregate() + ":" + a.purchaseOrderId() + ":" + a.sourceVersion(),
        true);
  }

  public void reconcileCase(UUID t, UUID id) {
    var c = service.base(t, id);
    var states =
        jdbc.query(
            "select state from aftersales_coordinations where tenant_id=? and aftersales_case_id=?"
                + " order by id",
            (r, n) -> r.getString(1),
            t,
            id);
    String upstream =
        states.isEmpty()
            ? "NO_PURCHASE"
            : states.contains("QUERY_PAYMENT")
                ? "QUERY_PAYMENT"
                : states.contains("UNKNOWN")
                    ? "UNKNOWN"
                    : states.stream().allMatch(s -> s.equals("REFUNDED"))
                        ? "REFUNDED"
                        : states.stream().allMatch(s -> s.equals("CANCELLED"))
                            ? "CANCELLED"
                            : states.getFirst();
    boolean moneyOutstanding =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "select exists(select 1 from aftersales_coordinations co join payment_attempts pa"
                    + " on pa.tenant_id=co.tenant_id and pa.purchase_order_id=co.purchase_order_id"
                    + " where co.tenant_id=? and co.aftersales_case_id=? and pa.state='PAID' and"
                    + " co.requested_refund_fen>coalesce((select sum(r.amount_fen) from"
                    + " refund_transactions r where r.tenant_id=co.tenant_id and"
                    + " r.aftersales_case_id=co.aftersales_case_id and"
                    + " r.supplier_purchase_order_id=co.purchase_order_id and"
                    + " r.direction='SUPPLIER' and r.state='VERIFIED'),0))",
                Boolean.class,
                t,
                id));
    String local;
    if (c.get("sourceState").equals("REJECTED")) local = "REJECTED";
    else if (c.get("sourceState").equals("ARBITRATION")) local = "MANUAL_REVIEW";
    else if (Set.of("REFUNDED", "CANCELLED").contains(c.get("sourceState"))
        && Set.of("NO_PURCHASE", "REFUNDED", "CANCELLED").contains(upstream)
        && !moneyOutstanding) local = "RESOLVED";
    else if ("RETURN_IN_TRANSIT".equals(c.get("state"))
        && !Set.of("RETURN_RECEIVED", "REFUND_PENDING", "REFUNDED", "REJECTED", "CANCELLED")
            .contains(upstream)) local = "RETURN_IN_TRANSIT";
    else local = states.isEmpty() ? "UNDER_REVIEW" : "SUPPLIER_COORDINATION";
    jdbc.update(
        "update aftersales_cases set"
            + " state=?,upstream_state=?,reason_code=?,version=version+1,updated_at=now() where"
            + " tenant_id=? and id=?",
        local,
        upstream,
        upstream.equals("QUERY_PAYMENT") ? "PAYMENT_STATUS_UNKNOWN" : null,
        t,
        id);
  }

  private void reconcileJob(
      Job j,
      AftersalesProviderAdapter.Source sourceSnapshot,
      java.util.List<AftersalesProviderAdapter.Supplier> snapshots) {
    if (!owns(j)) return;
    var c = service.base(j.tenant(), j.aggregate());
    stores.requireActive(j.tenant(), (UUID) c.get("storeId"), true);
    jdbc.query(
        "select id from sales_order_items where tenant_id=? and id=? for no key update",
        (r, n) -> r.getObject(1),
        j.tenant(),
        c.get("salesOrderItemId"));
    applySource(
        new Job(
            j.id(),
            j.tenant(),
            (UUID) c.get("salesOrderId"),
            "M4_AFTERSALE_SOURCE",
            (String) c.get("sourceCaseId"),
            null,
            j.attempts(),
            j.lease()),
        sourceSnapshot,
        false);
    var rows =
        jdbc.query(
            "select co.purchase_order_id,pa.id,pa.state,po.total_fen,pi.qty from"
                + " aftersales_coordinations co join purchase_orders po on"
                + " po.tenant_id=co.tenant_id and po.id=co.purchase_order_id join purchase_intents"
                + " pi on pi.tenant_id=po.tenant_id and pi.id=po.purchase_intent_id left join"
                + " payment_attempts pa on pa.tenant_id=po.tenant_id and pa.purchase_order_id=po.id"
                + " where co.tenant_id=? and co.aftersales_case_id=? and co.state in"
                + " ('QUERY_PAYMENT','UNKNOWN')",
            (r, n) ->
                new Object[] {
                  r.getObject(1, UUID.class),
                  r.getObject(2, UUID.class),
                  r.getString(3),
                  r.getLong(4),
                  r.getInt(5)
                },
            j.tenant(),
            j.aggregate());
    for (var row : rows) {
      if ("PAID".equals(row[2])) {
        long amount =
            java.math.BigInteger.valueOf((long) row[3])
                .multiply(java.math.BigInteger.valueOf((int) c.get("qty")))
                .divide(java.math.BigInteger.valueOf((int) row[4]))
                .longValueExact();
        jdbc.update(
            "update aftersales_coordinations set"
                + " state='PENDING',upstream_source_state='PENDING',requested_refund_fen=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and aftersales_case_id=? and purchase_order_id=?",
            amount,
            j.tenant(),
            j.aggregate(),
            row[0]);
      } else if (row[1] != null) {
        jdbc.update(
            "insert into outbox_events(tenant_id,aggregate_id,event_type,payload)"
                + " values(?,?,'M3_PAYMENT','{\"queryOnly\":true}'::jsonb) on"
                + " conflict(tenant_id,aggregate_id) where event_type='M3_PAYMENT' do update set"
                + " state=case when outbox_events.state='PROCESSING' then outbox_events.state else"
                + " 'NEW' end,available_at=now(),payload=excluded.payload",
            j.tenant(),
            row[1]);
      }
    }
    for (var snapshot : snapshots) applySupplier(j, snapshot, false);
    reconcileCase(j.tenant(), j.aggregate());
    done(j);
    activity.record(
        j.tenant(),
        (UUID) c.get("salesOrderId"),
        null,
        "AFTERSALES",
        "MOCK_AFTERSALE_RECONCILED",
        "AFTERSALE",
        j.aggregate(),
        null,
        "case-query:" + j.id(),
        true);
  }

  private void failure(Job j, RuntimeException ex) {
    if (!owns(j)) return;
    String code = ex instanceof ApiException a ? a.code() : "AFTERSALE_AUTHORITY_UNAVAILABLE";
    boolean blocked =
        ex instanceof ApiException
            && Set.of(
                    "REFUND_AMOUNT_EXCEEDED",
                    "AFTERSALE_IDENTITY_CHANGED",
                    "AFTERSALE_QUANTITY_EXCEEDED",
                    "PAYMENT_STATUS_UNKNOWN",
                    "PURCHASE_NOT_PAID",
                    "AUTHORIZATION_UNAVAILABLE",
                    "CAPABILITY_UNAVAILABLE")
                .contains(code);
    jdbc.update(
        "update outbox_events set state=?,available_at=now()+(?*interval '1"
            + " second'),last_error_code=?,lease_token=null,lease_until=null where tenant_id=? and"
            + " id=? and lease_token=?",
        blocked ? "BLOCKED" : j.attempts() >= 5 ? "DEAD" : "RETRY_WAIT",
        Math.min(60, 1 << j.attempts()),
        code,
        j.tenant(),
        j.id(),
        j.lease());
  }
}
