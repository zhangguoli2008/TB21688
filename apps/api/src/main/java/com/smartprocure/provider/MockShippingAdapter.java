package com.smartprocure.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MockShippingAdapter implements ShippingProviderAdapter {
  private final JdbcTemplate jdbc;
  private final RealChannelGuard guard;
  private final ObjectMapper mapper;
  private final M1Support support;
  private final TransactionTemplate tx;

  public MockShippingAdapter(
      JdbcTemplate j,
      RealChannelGuard g,
      ObjectMapper m,
      M1Support s,
      org.springframework.transaction.PlatformTransactionManager tm) {
    jdbc = j;
    guard = g;
    mapper = m;
    support = s;
    tx = new TransactionTemplate(tm);
  }

  public String provider() {
    return "MOCK_SHIPPING";
  }

  public Set<Capability> capabilities() {
    return Set.of(Capability.MOCK_SHIPMENT_SYNC, Capability.MOCK_SHIPMENT_QUERY);
  }

  private void allowed(UUID t) {
    if (!guard.demoEnabled()) throw M1Support.missing();
    guard.checkDatabaseFlags(t);
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from tenants where id=? and status='ACTIVE')",
            Boolean.class,
            t))) throw M1Support.conflict("TENANT_UNAVAILABLE");
  }

  private <T> T parse(String s, Class<T> type) {
    try {
      return mapper.readValue(s, type);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Source source(UUID t, UUID p, String e) {
    allowed(t);
    return jdbc
        .query(
            "select snapshot::text from mock_shipment_authority where tenant_id=? and"
                + " purchase_order_id=? and external_shipment_id=?",
            (r, n) -> parse(r.getString(1), Source.class),
            t,
            p,
            e)
        .stream()
        .findFirst()
        .orElseThrow(M1Support::missing);
  }

  private String behavior(UUID t, UUID shipment) {
    return jdbc.queryForObject(
        "select behavior from mock_shipment_configs where tenant_id=? and shipment_id=?",
        String.class,
        t,
        shipment);
  }

  public void submit(UUID t, Request r) {
    allowed(t);
    String b = behavior(t, r.shipmentId());
    if (b.equals("SUBMIT_5XX"))
      throw new ApiException(503, "SHIPMENT_SUBMIT_UNAVAILABLE", "Mock回填暂不可用");
    if (b.equals("CORRECTION_BLOCKED") && r.trackingVersion() > 1)
      throw M1Support.conflict("SHIPMENT_CORRECTION_BLOCKED");
    tx.executeWithoutResult(
        s -> {
          var ack = new Ack(r, "MOCK-ACK-" + r.providerKey());
          jdbc.update(
              "insert into"
                  + " mock_shipment_ack_authority(tenant_id,provider_key,shipment_id,tracking_version,snapshot,submit_calls)"
                  + " values(?,?,?,?,?::jsonb,1) on conflict(tenant_id,provider_key) do update set"
                  + " submit_calls=mock_shipment_ack_authority.submit_calls+1",
              t,
              r.providerKey(),
              r.shipmentId(),
              r.trackingVersion(),
              support.json(ack));
        });
    if (b.equals("SUBMIT_TIMEOUT_AFTER"))
      throw new ApiException(503, "SHIPMENT_SYNC_UNKNOWN", "Mock回填结果待查询");
  }

  public Optional<Ack> query(UUID t, Request r) {
    allowed(t);
    String b = behavior(t, r.shipmentId());
    if (b.equals("QUERY_TIMEOUT"))
      throw new ApiException(503, "SHIPMENT_QUERY_TIMEOUT", "Mock查询暂不可用");
    var rows =
        jdbc.query(
            "select snapshot::text from mock_shipment_ack_authority where tenant_id=? and"
                + " provider_key=?",
            (rs, n) -> parse(rs.getString(1), Ack.class),
            t,
            r.providerKey());
    if (rows.isEmpty()) return Optional.empty();
    var a = rows.getFirst();
    if (b.equals("AUTHORITY_MISMATCH"))
      a =
          new Ack(
              new Request(
                  r.shipmentId(),
                  r.salesOrderId(),
                  r.salesOrderItemId(),
                  r.qty(),
                  r.trackingVersion() + 1,
                  r.carrierCode(),
                  r.trackingNumber(),
                  r.providerKey()),
              a.platformAck());
    return Optional.of(a);
  }
}
