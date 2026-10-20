package com.smartprocure.provider;

import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class MockSupplierAdapter implements ProviderAdapter {
    @Override public String provider() { return "MOCK_SUPPLIER"; }
    @Override public Set<Capability> capabilities() { return Set.of(Capability.DEMO_PROBE); }
}
