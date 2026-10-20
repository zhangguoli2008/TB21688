package com.smartprocure.auth;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.ApiErrors;
import com.smartprocure.provider.RealChannelGuard;
import com.smartprocure.tenant.TenantIdentityRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfiguration {
    @Bean HttpSessionCsrfTokenRepository csrfRepository() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, ApiErrors errors, TenantIdentityRepository identities, RealChannelGuard guard, AuditService audit, HttpSessionCsrfTokenRepository csrf) throws Exception {
        http.csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(config -> config.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(config -> config.disable())
                .formLogin(config -> config.disable()).httpBasic(config -> config.disable()).logout(config -> config.disable())
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, ex) -> errors.write(request, response, 401, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, ex) -> errors.write(request, response, 403, "FORBIDDEN", "权限不足或CSRF校验失败")))
                .authorizeHttpRequests(config -> {
                    config.requestMatchers("/error", "/api/v1/auth/csrf", "/api/v1/auth/login", "/actuator/health/liveness").permitAll();
                    if (guard.demoEnabled()) { config.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/contracts/openapi.yaml").permitAll(); }
                    else { config.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/contracts/**", "/v3/api-docs/**").denyAll(); }
                    config.requestMatchers("/api/v1/system/status").hasAuthority("system.read")
                            .requestMatchers("/api/v1/demo/**").hasAuthority("demo.read")
                            .anyRequest().authenticated();
                })
                .addFilterBefore(new SessionIdentityFilter(identities, guard, errors, audit), CsrfFilter.class);
        return http.build();
    }
}
