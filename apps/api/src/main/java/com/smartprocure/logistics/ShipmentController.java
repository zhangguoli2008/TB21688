package com.smartprocure.logistics;

import com.smartprocure.common.*;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ShipmentController {
  private final ShipmentService service;

  public ShipmentController(ShipmentService s) {
    service = s;
  }

  @GetMapping("/shipments")
  public ApiEnvelope<?> list(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) UUID storeId,
      @RequestParam(required = false) UUID purchaseOrderId,
      @RequestParam(required = false) UUID salesOrderId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String syncState,
      HttpServletRequest r) {
    return ApiEnvelope.of(
        service.list(p, limit, offset, storeId, purchaseOrderId, salesOrderId, state, syncState),
        r);
  }

  @GetMapping("/shipments/{id}")
  public ApiEnvelope<?> get(
      @AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id, HttpServletRequest r) {
    return ApiEnvelope.of(service.detail(p, id), r);
  }

  @PostMapping("/mock/purchases/{id}/shipment-events")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public ApiEnvelope<?> event(
      @AuthenticationPrincipal TenantPrincipal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ShipmentService.Event e,
      HttpServletRequest r) {
    return ApiEnvelope.of(service.event(p, id, key, e), r);
  }

  @PatchMapping("/mock/shipments/{id}/sync-config")
  public ApiEnvelope<?> config(
      @AuthenticationPrincipal TenantPrincipal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ShipmentService.ConfigUpdate e,
      HttpServletRequest r) {
    return ApiEnvelope.of(service.configure(p, id, key, e), r);
  }

  @PostMapping("/shipments/{id}/sync-retry")
  public ApiEnvelope<?> retry(
      @AuthenticationPrincipal TenantPrincipal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    return ApiEnvelope.of(service.retry(p, id, key), r);
  }
}
