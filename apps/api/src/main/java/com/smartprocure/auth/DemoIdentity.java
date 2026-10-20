package com.smartprocure.auth;

import java.io.Serializable;
import java.util.UUID;

public record DemoIdentity(UUID userId, UUID tenantId) implements Serializable {
    public static final String SESSION_ATTRIBUTE = DemoIdentity.class.getName();
}
