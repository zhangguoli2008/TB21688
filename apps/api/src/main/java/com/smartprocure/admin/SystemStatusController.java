package com.smartprocure.admin;

import com.smartprocure.common.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class SystemStatusController {
    record ServiceStatus(String name, String status) { }
    record SystemStatus(String mode, boolean realChannelsEnabled, List<ServiceStatus> services) { }
    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;
    private final ConnectionFactory rabbit;
    public SystemStatusController(JdbcTemplate jdbc, RedisConnectionFactory redis, ConnectionFactory rabbit) { this.jdbc = jdbc; this.redis = redis; this.rabbit = rabbit; }
    @GetMapping("/api/v1/system/status")
    ApiEnvelope<SystemStatus> status(HttpServletRequest request) {
        return ApiEnvelope.of(new SystemStatus("MOCK", false, List.of(new ServiceStatus("api", "UP"),
                new ServiceStatus("postgresql", check(() -> Integer.valueOf(1).equals(jdbc.queryForObject("select 1", Integer.class)))),
                new ServiceStatus("redis", check(() -> { try (var connection = redis.getConnection()) { return "PONG".equals(connection.ping()); } })),
                new ServiceStatus("rabbitmq", check(() -> { try (var connection = rabbit.createConnection()) { return connection.isOpen(); } })))), request);
    }
    private String check(java.util.concurrent.Callable<Boolean> probe) { try { return Boolean.TRUE.equals(probe.call()) ? "UP" : "DOWN"; } catch (Exception ex) { return "DOWN"; } }
}
