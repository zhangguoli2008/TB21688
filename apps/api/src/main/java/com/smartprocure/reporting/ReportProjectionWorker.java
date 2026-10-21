package com.smartprocure.reporting;

import com.smartprocure.common.M1Support;
import com.smartprocure.common.M4Activity;
import com.smartprocure.store.StoreService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Local projection only. Domain writes and refresh Outbox entries commit together. */
@Service
public class ReportProjectionWorker {
  private record Job(UUID id, UUID tenant, UUID order, UUID lease, int attempts) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final M4Activity activity;
  private final StoreService stores;

  public ReportProjectionWorker(
      JdbcTemplate jdbc,
      org.springframework.transaction.PlatformTransactionManager manager,
      M4Activity activity,
      StoreService stores) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
    this.activity = activity;
    this.stores = stores;
  }

  public void runOnce() {
    stores.requireDemo();
    bootstrap();
    for (int i = 0; i < 40; i++) {
      Job j = tx.execute(s -> claim());
      if (j == null) return;
      try {
        tx.executeWithoutResult(s -> apply(j));
      } catch (RuntimeException ex) {
        tx.executeWithoutResult(
            s ->
                jdbc.update(
                    "update outbox_events set"
                        + " state=?,last_error_code='REPORT_PROJECTION_UNAVAILABLE',available_at=now()+interval"
                        + " '5 seconds',lease_until=null,lease_token=null where tenant_id=? and"
                        + " id=? and lease_token=?",
                    j.attempts() >= 5 ? "DEAD" : "RETRY_WAIT",
                    j.tenant(),
                    j.id(),
                    j.lease()));
      }
    }
  }

  private void bootstrap() {
    tx.executeWithoutResult(
        s -> {
          var rows =
              jdbc.query(
                  "select o.tenant_id,o.id from sales_orders o join tenants t on t.id=o.tenant_id"
                      + " where t.status='ACTIVE' and not exists(select 1 from"
                      + " report_order_projections p where p.tenant_id=o.tenant_id and"
                      + " p.sales_order_id=o.id) and not exists(select 1 from outbox_events e where"
                      + " e.tenant_id=o.tenant_id and e.aggregate_id=o.id and"
                      + " e.event_type='M4_REPORT_REFRESH' and e.state in"
                      + " ('NEW','PROCESSING','RETRY_WAIT','DEAD')) order by o.id limit 20",
                  (r, n) -> new UUID[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class)});
          for (var row : rows) activity.refresh(row[0], row[1]);
        });
  }

  private Job claim() {
    var rows =
        jdbc.query(
            "select e.id,e.tenant_id,e.aggregate_id,e.attempts from outbox_events e join tenants t"
                + " on t.id=e.tenant_id where t.status='ACTIVE' and"
                + " e.event_type='M4_REPORT_REFRESH' and ((e.state in ('NEW','RETRY_WAIT') and"
                + " e.available_at<=now()) or(e.state='PROCESSING' and e.lease_until<now())) order"
                + " by e.available_at,e.id for update of e skip locked limit 1",
            (r, n) ->
                new Job(
                    r.getObject(1, UUID.class),
                    r.getObject(2, UUID.class),
                    r.getObject(3, UUID.class),
                    UUID.randomUUID(),
                    r.getInt(4) + 1));
    if (rows.isEmpty()) return null;
    Job j = rows.getFirst();
    if (j.attempts() > 5) {
      jdbc.update(
          "update outbox_events set"
              + " state='DEAD',lease_token=null,lease_until=null,last_error_code='REPORT_LEASE_EXHAUSTED'"
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

  private void apply(Job j) {
    if (jdbc.query(
            "select id from outbox_events where tenant_id=? and id=? and lease_token=? and"
                + " state='PROCESSING' and lease_until>clock_timestamp() for update",
            (r, n) -> r.getObject(1, UUID.class),
            j.tenant(),
            j.id(),
            j.lease())
        .isEmpty()) return;
    // Serializes same-order snapshots before the single aggregate statement obtains its MVCC
    // snapshot.
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        j.tenant() + ":" + j.order());
    jdbc.update(
        """
        insert into report_order_projections(tenant_id,sales_order_id,store_id,external_order_id,order_created_at,sales_paid_fen,purchase_paid_fen,estimated_purchase_fen,estimated_profit_fen,sales_refunded_fen,supplier_refunded_fen,unreconciled_count,unreconciled_payment_fen,pending_supplier_refund_fen,as_of)
        select o.tenant_id,o.id,o.store_id,o.external_order_id,o.created_at,case when o.paid_at is null then 0 else o.paid_amount_fen end,
          pay.paid,est.cost,est.profit,ref.sales,ref.supplier,pay.unresolved+po.unresolved+cas.unresolved+shipping.unresolved,pay.pending,cas.pending,clock_timestamp()
        from sales_orders o
        cross join lateral(select coalesce(sum(a.amount_fen) filter(where a.state='PAID'),0)::bigint paid,
          count(*) filter(where a.state in ('PAYING','UNKNOWN','MANUAL_RECONCILIATION')) unresolved,
          coalesce(sum(a.amount_fen) filter(where a.state in ('PAYING','UNKNOWN','MANUAL_RECONCILIATION')),0)::bigint pending
          from payment_attempts a join purchase_orders p on p.tenant_id=a.tenant_id and p.id=a.purchase_order_id
          join purchase_intents i on i.tenant_id=p.tenant_id and i.id=p.purchase_intent_id
          join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id where si.tenant_id=o.tenant_id and si.sales_order_id=o.id) pay
        cross join lateral(select coalesce(sum(i.estimated_total_fen),0)::bigint cost,
          coalesce(sum(trunc(si.paid_allocated_fen::numeric*i.qty/si.qty)-i.estimated_total_fen-coalesce((i.policy_snapshot->'effective'->>'platformFeeFen')::bigint,0)-coalesce((i.policy_snapshot->'effective'->>'reserveFen')::bigint,0)),0)::bigint profit
          from purchase_intents i join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id where si.tenant_id=o.tenant_id and si.sales_order_id=o.id and i.state not in ('FAILED','BLOCKED')) est
        cross join lateral(select coalesce(sum(r.amount_fen) filter(where r.direction='SALES'),0)::bigint sales,coalesce(sum(r.amount_fen) filter(where r.direction='SUPPLIER'),0)::bigint supplier
          from refund_transactions r join aftersales_cases c on c.tenant_id=r.tenant_id and c.id=r.aftersales_case_id where c.tenant_id=o.tenant_id and c.sales_order_id=o.id and r.state='VERIFIED') ref
        cross join lateral(select count(*) unresolved from purchase_intents i join sales_order_items si on si.tenant_id=i.tenant_id and si.id=i.sales_order_item_id where si.tenant_id=o.tenant_id and si.sales_order_id=o.id and i.state in ('CREATING','CREATE_UNKNOWN','REVIEW_REQUIRED')) po
        cross join lateral(select count(*) filter(where c.state not in ('RESOLVED','REJECTED')) unresolved,
          coalesce((select sum(greatest(ac.requested_refund_fen-coalesce((select sum(r.amount_fen) from refund_transactions r where r.tenant_id=ac.tenant_id and r.aftersales_case_id=ac.aftersales_case_id and r.supplier_purchase_order_id=ac.purchase_order_id and r.direction='SUPPLIER' and r.state='VERIFIED'),0),0)) from aftersales_coordinations ac join aftersales_cases c2 on c2.tenant_id=ac.tenant_id and c2.id=ac.aftersales_case_id where c2.tenant_id=o.tenant_id and c2.sales_order_id=o.id and c2.state<>'REJECTED'),0)::bigint pending
          from aftersales_cases c where c.tenant_id=o.tenant_id and c.sales_order_id=o.id) cas
        cross join lateral(select count(*) unresolved from shipment_syncs ss join shipments sh on sh.tenant_id=ss.tenant_id and sh.id=ss.shipment_id and sh.tracking_version=ss.tracking_version where ss.tenant_id=o.tenant_id and ss.sales_order_id=o.id and ss.state<>'ACKED') shipping
        where o.tenant_id=? and o.id=?
        on conflict(tenant_id,sales_order_id) do update set store_id=excluded.store_id,external_order_id=excluded.external_order_id,order_created_at=excluded.order_created_at,sales_paid_fen=excluded.sales_paid_fen,purchase_paid_fen=excluded.purchase_paid_fen,estimated_purchase_fen=excluded.estimated_purchase_fen,estimated_profit_fen=excluded.estimated_profit_fen,sales_refunded_fen=excluded.sales_refunded_fen,supplier_refunded_fen=excluded.supplier_refunded_fen,unreconciled_count=excluded.unreconciled_count,unreconciled_payment_fen=excluded.unreconciled_payment_fen,pending_supplier_refund_fen=excluded.pending_supplier_refund_fen,as_of=excluded.as_of
        """,
        j.tenant(),
        j.order());
    if (jdbc.update(
            "update outbox_events set"
                + " state='DONE',published_at=now(),lease_token=null,lease_until=null,last_error_code=null"
                + " where tenant_id=? and id=? and lease_token=? and state='PROCESSING'",
            j.tenant(),
            j.id(),
            j.lease())
        != 1) throw M1Support.conflict("WORKER_LEASE_LOST");
  }
}
