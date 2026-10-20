package com.smartprocure.provider;

import com.smartprocure.common.ApiException;
import java.time.Instant;
import java.util.Set;

/** Internal Mock boundary. No official external API or commerce workflow is implemented in M0. */
public interface ProviderAdapter {
    enum Capability { DEMO_PROBE, MOCK_ORDER_READ }
    record Probe(String provider, String mode, Set<Capability> capabilities, Instant observedAt) { }
    String provider();
    Set<Capability> capabilities();
    default Probe probe() { return new Probe(provider(), "MOCK", capabilities(), Instant.now()); }
    default void requireRealCapability() { throw new ApiException(422, "CAPABILITY_UNAVAILABLE", "真实平台能力待授权，需转人工"); }
}
