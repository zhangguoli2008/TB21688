package com.smartprocure.provider;

import com.smartprocure.common.ApiException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@DependsOn("flywayInitializer")
public class RealChannelGuard implements InitializingBean {
    private final Environment environment;
    private final JdbcTemplate jdbc;
    public RealChannelGuard(Environment environment, JdbcTemplate jdbc) { this.environment = environment; this.jdbc = jdbc; }
    public static void validateConfiguration(Environment environment) {
        if (!"MOCK".equals(environment.getProperty("app.provider-mode", "MOCK"))
                || environment.getProperty("app.real-platform-enabled", Boolean.class, false)
                || environment.getProperty("app.real-procurement-enabled", Boolean.class, false)
                || environment.getProperty("app.real-payment-enabled", Boolean.class, false)) {
            throw new IllegalStateException("M0 accepts only MOCK with every real-channel flag disabled");
        }
        if (environment.acceptsProfiles(Profiles.of("prod", "production")) && environment.acceptsProfiles(Profiles.of("demo", "test"))) {
            throw new IllegalStateException("Production and demo/test profiles cannot be combined");
        }
    }
    public boolean demoEnabled() { return environment.acceptsProfiles(Profiles.of("demo", "test")); }
    public void checkDatabaseFlags(UUID tenantId) {
        // Only the reserved real_ namespace is a real-channel switch. Ordinary Mock flags are unrelated.
        String sql = "select count(*) from feature_flags where enabled=true and left(lower(flag_name),5)='real_' and ";
        Integer count = tenantId == null
                ? jdbc.queryForObject(sql + "tenant_id is null", Integer.class)
                : jdbc.queryForObject(sql + "(tenant_id is null or tenant_id=?)", Integer.class, tenantId);
        if (count != null && count > 0) { throw new ApiException(503, "CAPABILITY_UNAVAILABLE", "真实通道未获准，服务已失败关闭"); }
    }
    @Override public void afterPropertiesSet() { validateConfiguration(environment); checkDatabaseFlags(null); }
}
