package com.smartprocure.tenant;

import java.util.List;
import java.util.UUID;

public record TenantPrincipal(UUID userId, String email, List<UUID> tenantIds, UUID currentTenantId,
                              String tenantName, List<String> roles, List<String> permissions, String mode) { }
