package com.smartprocure.auth;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.ApiEnvelope;
import com.smartprocure.common.ApiException;
import com.smartprocure.common.RequestIdFilter;
import com.smartprocure.provider.RealChannelGuard;
import com.smartprocure.tenant.TenantIdentityRepository;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    enum Persona { OWNER_A, SUPPORT_A, OWNER_B }
    record LoginRequest(@NotNull Persona persona) { }
    record CsrfResponse(String token, String headerName) { }
    record LogoutResponse(boolean loggedOut) { }
    private final TenantIdentityRepository identities;
    private final RealChannelGuard guard;
    private final HttpSessionCsrfTokenRepository csrf;
    private final AuditService audit;
    public AuthController(TenantIdentityRepository identities, RealChannelGuard guard, HttpSessionCsrfTokenRepository csrf, AuditService audit) {
        this.identities = identities; this.guard = guard; this.csrf = csrf; this.audit = audit;
    }
    @GetMapping("/auth/csrf")
    ApiEnvelope<CsrfResponse> csrf(CsrfToken token, HttpServletRequest request) {
        return ApiEnvelope.of(new CsrfResponse(token.getToken(), token.getHeaderName()), request);
    }
    @PostMapping("/auth/login")
    ApiEnvelope<TenantPrincipal> login(@Valid @RequestBody LoginRequest login, HttpServletRequest request, HttpServletResponse response) {
        if (!guard.demoEnabled()) { throw new ApiException(404, "NOT_FOUND", "演示认证未启用"); }
        String user = switch (login.persona()) {
            case OWNER_A -> "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1";
            case SUPPORT_A -> "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2";
            case OWNER_B -> "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1";
        };
        UUID tenant = UUID.fromString(login.persona() == Persona.OWNER_B ? "22222222-2222-4222-8222-222222222222" : "11111111-1111-4111-8111-111111111111");
        TenantPrincipal principal = identities.findActive(UUID.fromString(user), tenant)
                .orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "演示身份不可用"));
        guard.checkDatabaseFlags(tenant);
        audit.record(principal, "DEMO_LOGIN", 200, RequestIdFilter.requestId(request));
        request.getSession(true);
        request.changeSessionId();
        request.getSession().setAttribute(DemoIdentity.SESSION_ATTRIBUTE, new DemoIdentity(principal.userId(), tenant));
        csrf.saveToken(null, request, response);
        return ApiEnvelope.of(principal, request);
    }
    @PostMapping("/auth/logout")
    ApiEnvelope<LogoutResponse> logout(@AuthenticationPrincipal TenantPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        audit.record(principal, "DEMO_LOGOUT", 200, RequestIdFilter.requestId(request));
        if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
        var cookie = new jakarta.servlet.http.Cookie("SPSESSION", "");
        cookie.setPath("/"); cookie.setHttpOnly(true); cookie.setSecure(request.isSecure()); cookie.setMaxAge(0); cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return ApiEnvelope.of(new LogoutResponse(true), request);
    }
    @GetMapping("/me")
    ApiEnvelope<TenantPrincipal> me(@AuthenticationPrincipal TenantPrincipal principal, HttpServletRequest request) { return ApiEnvelope.of(principal, request); }
}
