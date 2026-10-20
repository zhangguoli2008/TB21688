package com.smartprocure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetAddress;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@EnabledIfEnvironmentVariable(named = "M0_TEST_DB_URL", matches = ".+")
class DemoBindingIT {
    @Autowired ServletWebServerApplicationContext context;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("M0_TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("M0_TEST_DB_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("M0_TEST_DB_PASSWORD", ""));
    }
    @Test void passwordlessDemoBindsOnlyToLoopbackByDefault() {
        var server = (TomcatWebServer) context.getWebServer();
        var address = (InetAddress) server.getTomcat().getConnector().getProperty("address");
        assertThat(address).isNotNull();
        assertThat(address.isLoopbackAddress()).isTrue();
    }
}
