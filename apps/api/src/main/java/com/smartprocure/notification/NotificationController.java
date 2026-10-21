package com.smartprocure.notification;

import com.smartprocure.common.*;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  @GetMapping("/notifications")
  ApiEnvelope<M1Support.Page<NotificationService.Notification>> list(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestParam(required = false) UUID storeId,
      @RequestParam(required = false) Boolean unread,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest r) {
    return ApiEnvelope.of(service.list(p, storeId, unread, limit, offset), r);
  }

  @PostMapping("/notifications/{notificationId}/read")
  ApiEnvelope<NotificationService.Notification> read(
      @AuthenticationPrincipal TenantPrincipal p,
      @PathVariable UUID notificationId,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    return ApiEnvelope.of(service.read(p, notificationId, key), r);
  }

  @GetMapping("/orders/{orderId}/activity")
  ApiEnvelope<M1Support.Page<NotificationService.Activity>> activity(
      @AuthenticationPrincipal TenantPrincipal p,
      @PathVariable UUID orderId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest r) {
    return ApiEnvelope.of(service.activity(p, orderId, limit, offset), r);
  }
}
