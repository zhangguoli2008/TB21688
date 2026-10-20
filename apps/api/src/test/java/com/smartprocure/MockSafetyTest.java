package com.smartprocure;

import com.smartprocure.common.ApiException;
import com.smartprocure.provider.MockSalesAdapter;
import com.smartprocure.provider.MockSupplierAdapter;
import com.smartprocure.provider.ProviderAdapter;
import com.smartprocure.provider.RealChannelGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MockSafetyTest {
    @Test void safeDefaultConfigurationCanStart() {
        assertThatCode(() -> RealChannelGuard.validateConfiguration(new MockEnvironment())).doesNotThrowAnyException();
    }
    @ParameterizedTest
    @ValueSource(strings = {"REAL", "LIVE", "mock", ""})
    void nonMockModeCannotStart(String mode) {
        assertThatThrownBy(() -> RealChannelGuard.validateConfiguration(new MockEnvironment().withProperty("app.provider-mode", mode)))
                .isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"app.real-platform-enabled", "app.real-procurement-enabled", "app.real-payment-enabled"})
    void realCommerceFlagCannotStart(String flag) {
        assertThatThrownBy(() -> RealChannelGuard.validateConfiguration(new MockEnvironment().withProperty(flag, "true")))
                .isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"prod", "production"})
    void demoCannotBeCombinedWithProduction(String production) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(production, "demo");
        assertThatThrownBy(() -> RealChannelGuard.validateConfiguration(environment)).isInstanceOf(IllegalStateException.class);
    }
    @Test void mockAdaptersHaveProbeOnlyAndRefuseRealCapabilities() {
        for (ProviderAdapter adapter : List.of(new MockSalesAdapter(), new MockSupplierAdapter())) {
            var probe = adapter.probe();
            assertThat(probe.mode()).isEqualTo("MOCK");
            assertThat(probe.capabilities()).containsExactly(ProviderAdapter.Capability.DEMO_PROBE);
            assertThat(probe.observedAt()).isBeforeOrEqualTo(Instant.now());
            assertThatThrownBy(adapter::requireRealCapability).isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(422);
                assertThat(ex.code()).isEqualTo("CAPABILITY_UNAVAILABLE");
            });
        }
    }
}
