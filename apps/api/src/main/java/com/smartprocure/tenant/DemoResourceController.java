package com.smartprocure.tenant;

import com.smartprocure.common.ApiEnvelope;
import com.smartprocure.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/demo/resources")
public class DemoResourceController {
    record DemoResource(UUID id, String label, UUID tenantId) { }
    @GetMapping("/{id}")
    ApiEnvelope<DemoResource> resource(@PathVariable UUID id, @AuthenticationPrincipal TenantPrincipal principal, HttpServletRequest request) {
        if (!principal.currentTenantId().equals(id)) { throw new ApiException(404, "NOT_FOUND", "资源不存在"); }
        return ApiEnvelope.of(new DemoResource(id, principal.tenantName() + "模拟资源", principal.currentTenantId()), request);
    }
}
