package com.smartprocure.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MockAftersalesAdapter implements AftersalesProviderAdapter {
  private final JdbcTemplate jdbc;
  private final RealChannelGuard guard;
  private final ObjectMapper mapper;

  public MockAftersalesAdapter(JdbcTemplate j, RealChannelGuard g, ObjectMapper m) {
    jdbc = j;
    guard = g;
    mapper = m;
  }

  public String provider() {
    return "MOCK_AFTERSALES";
  }

  public Set<Capability> capabilities() {
    return Set.of(Capability.MOCK_AFTERSALES_QUERY);
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

  private <T> T read(String s, Class<T> type) {
    try {
      return mapper.readValue(s, type);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public Source source(UUID t, UUID o, String e) {
    allowed(t);
    return jdbc
        .query(
            "select snapshot::text from mock_aftersale_authority where tenant_id=? and"
                + " sales_order_id=? and source_case_id=?",
            (r, n) -> read(r.getString(1), Source.class),
            t,
            o,
            e)
        .stream()
        .findFirst()
        .orElseThrow(M1Support::missing);
  }

  public Supplier supplier(UUID t, UUID c, UUID p) {
    allowed(t);
    return jdbc
        .query(
            "select snapshot::text from mock_supplier_aftersale_authority where tenant_id=? and"
                + " aftersales_case_id=? and purchase_order_id=?",
            (r, n) -> read(r.getString(1), Supplier.class),
            t,
            c,
            p)
        .stream()
        .findFirst()
        .orElseThrow(M1Support::missing);
  }
}
