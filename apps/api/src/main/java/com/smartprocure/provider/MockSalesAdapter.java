package com.smartprocure.provider;

import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class MockSalesAdapter implements ProviderAdapter {
    @Override public String provider() { return "MOCK_SALES"; }
    @Override public Set<Capability> capabilities() { return Set.of(Capability.DEMO_PROBE); }
}
