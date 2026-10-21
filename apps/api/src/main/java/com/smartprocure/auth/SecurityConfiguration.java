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
import org.springframework.http.HttpMethod;

@Configuration
public class SecurityConfiguration {
    @Bean HttpSessionCsrfTokenRepository csrfRepository() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, ApiErrors errors, TenantIdentityRepository identities, RealChannelGuard guard, AuditService audit, HttpSessionCsrfTokenRepository csrf) throws Exception {
        http.csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/v1/webhooks/mock/stores/*"))
                .securityContext(config -> config.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(config -> config.disable())
                .formLogin(config -> config.disable()).httpBasic(config -> config.disable()).logout(config -> config.disable())
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, ex) -> errors.write(request, response, 401, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, ex) -> errors.write(request, response, 403, "FORBIDDEN", "权限不足或CSRF校验失败")))
                .authorizeHttpRequests(config -> {
                    config.requestMatchers("/error", "/api/v1/auth/csrf", "/api/v1/auth/login", "/actuator/health/liveness").permitAll();
                    config.requestMatchers(HttpMethod.POST,"/api/v1/webhooks/mock/stores/*").permitAll();
                    if (guard.demoEnabled()) { config.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/contracts/openapi.yaml").permitAll(); }
                    else { config.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/contracts/**", "/v3/api-docs/**").denyAll(); }
                    config.requestMatchers("/api/v1/system/status").hasAuthority("system.read")
                            .requestMatchers("/api/v1/demo/**").hasAuthority("demo.read")
                            .requestMatchers(HttpMethod.GET,"/api/v1/stores","/api/v1/stores/*").hasAuthority("stores.read")
                            .requestMatchers(HttpMethod.POST,"/api/v1/stores").hasAuthority("stores.manage")
                            .requestMatchers(HttpMethod.PATCH,"/api/v1/stores/*").hasAuthority("stores.manage")
                            .requestMatchers(HttpMethod.GET,"/api/v1/orders","/api/v1/orders/*").hasAuthority("orders.read")
                            .requestMatchers(HttpMethod.POST,"/api/v1/mock/stores/*/scenarios").hasAuthority("orders.simulate")
                            .requestMatchers(HttpMethod.GET,"/api/v1/supplier-offers","/api/v1/sku-mappings","/api/v1/procurement-policy").hasAuthority("sourcing.read")
                            .requestMatchers(HttpMethod.POST,"/api/v1/sku-mappings","/api/v1/supplier-offers/*/quotes").hasAuthority("sourcing.manage")
                            .requestMatchers(HttpMethod.PATCH,"/api/v1/sku-mappings/*","/api/v1/supplier-offers/*/mock-config").hasAuthority("sourcing.manage")
                            .requestMatchers(HttpMethod.GET,"/api/v1/procurement-policies/*","/api/v1/purchase-intents","/api/v1/purchase-intents/*","/api/v1/purchases","/api/v1/purchases/*").hasAuthority("procurement.read")
                            .requestMatchers(HttpMethod.POST,"/api/v1/purchase-intents","/api/v1/purchase-intents/evaluate").hasAuthority("procurement.manage")
                            .requestMatchers(HttpMethod.PUT,"/api/v1/procurement-policy","/api/v1/procurement-policies/*").hasAuthority("procurement.manage")
                            .requestMatchers(HttpMethod.PUT,"/api/v1/payment-policy").hasAuthority("paymentPolicy.manage")
                            .anyRequest().authenticated();
                })
                .addFilterBefore(new SessionIdentityFilter(identities, guard, errors, audit), CsrfFilter.class);
        return http.build();
    }
}
