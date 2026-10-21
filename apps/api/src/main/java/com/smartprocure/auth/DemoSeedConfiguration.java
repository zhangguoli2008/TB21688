package com.smartprocure.auth;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;

@Configuration
@Profile({"demo", "test"})
public class DemoSeedConfiguration {
    @Bean
    @DependsOn("realChannelGuard")
    InitializingBean demoSeed(DataSource dataSource) {
        return () -> new ResourceDatabasePopulator(new ClassPathResource("db/demo/seed.sql"), new ClassPathResource("db/demo/m1-seed.sql"), new ClassPathResource("db/demo/m2-seed.sql")).execute(dataSource);
    }
}
