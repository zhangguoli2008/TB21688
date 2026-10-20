package com.smartprocure.auth;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.ApiErrors;
import com.smartprocure.common.ApiException;
import com.smartprocure.common.RequestIdFilter;
import com.smartprocure.provider.RealChannelGuard;
import com.smartprocure.tenant.TenantIdentityRepository;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.IOException;

public class SessionIdentityFilter extends OncePerRequestFilter {
    private final TenantIdentityRepository identities;
    private final RealChannelGuard guard;
    private final ApiErrors errors;
    private final AuditService audit;
    public SessionIdentityFilter(TenantIdentityRepository identities, RealChannelGuard guard, ApiErrors errors, AuditService audit) {
        this.identities = identities; this.guard = guard; this.errors = errors; this.audit = audit;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        TenantPrincipal principal = null;
        var buffered = new ContentCachingResponseWrapper(response);
        try {
            guard.checkDatabaseFlags(null);
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute(DemoIdentity.SESSION_ATTRIBUTE) instanceof DemoIdentity identity) {
                principal = guard.demoEnabled() ? identities.findActive(identity.userId(), identity.tenantId()).orElse(null) : null;
                if (principal == null) { session.invalidate(); }
                else {
                    boolean publicAuth = request.getRequestURI().equals("/api/v1/auth/csrf") || request.getRequestURI().equals("/api/v1/auth/login");
                    if (!publicAuth) { guard.checkDatabaseFlags(principal.currentTenantId()); }
                    var authorities = principal.permissions().stream().map(SimpleGrantedAuthority::new).toList();
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
                }
            }
            chain.doFilter(request, buffered);
        } catch (ApiException ex) { buffered.resetBuffer(); errors.write(request, buffered, ex.status(), ex.code(), ex.getMessage()); }
        catch (org.springframework.dao.DataAccessException ex) { buffered.resetBuffer(); errors.write(request, buffered, 503, "SERVICE_UNAVAILABLE", "服务暂不可用"); }
        finally {
            try {
                // M0 APIs do not stream. Commit their result only after the audit write succeeds.
                try { audit.record(principal, "API_REQUEST", buffered.getStatus(), RequestIdFilter.requestId(request)); }
                catch (org.springframework.dao.DataAccessException ex) {
                    HttpSession session = request.getSession(false);
                    if (session != null) { session.invalidate(); }
                    buffered.resetBuffer();
                    errors.write(request, buffered, 503, "SERVICE_UNAVAILABLE", "审计服务暂不可用");
                }
                buffered.copyBodyToResponse();
            }
            finally { SecurityContextHolder.clearContext(); }
        }
    }
}
