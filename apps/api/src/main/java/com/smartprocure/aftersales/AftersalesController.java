package com.smartprocure.aftersales;

import com.smartprocure.common.*;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AftersalesController {
  private final AftersalesService s;

  public AftersalesController(AftersalesService service) {
    s = service;
  }

  @GetMapping("/aftersales")
  public ApiEnvelope<?> list(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) UUID storeId,
      @RequestParam(required = false) UUID salesOrderId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String sourceState,
      @RequestParam(required = false) String upstreamState,
      HttpServletRequest r) {
    return ApiEnvelope.of(
        s.list(p, limit, offset, storeId, salesOrderId, state, type, sourceState, upstreamState),
        r);
  }

  @GetMapping("/aftersales/{id}")
  public ApiEnvelope<?> get(
      @AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id, HttpServletRequest r) {
    return ApiEnvelope.of(s.detail(p, id), r);
  }

  @PatchMapping("/aftersales/{id}")
  public ApiEnvelope<?> update(
      @AuthenticationPrincipal TenantPrincipal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody AftersalesService.Update u,
      HttpServletRequest r) {
    return ApiEnvelope.of(s.update(p, id, key, u), r);
  }

  @PostMapping("/mock/orders/{id}/aftersale-events")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public ApiEnvelope<?> event(
      @AuthenticationPrincipal TenantPrincipal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody AftersalesService.Event e,
      HttpServletRequest r) {
    return ApiEnvelope.of(s.event(p, id, key, e), r);
  }

  @PostMapping("/mock/aftersales/{id}/supplier-events")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public ApiEnvelope<?> supplier(
      @AuthenticationPrincipal TenantPrincipal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody AftersalesService.SupplierEvent e,
      HttpServletRequest r) {
    return ApiEnvelope.of(s.supplierEvent(p, id, key, e), r);
  }

  @PostMapping("/aftersales/{id}/reconcile")
  public ApiEnvelope<?> reconcile(
      @AuthenticationPrincipal TenantPrincipal p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    return ApiEnvelope.of(s.reconcile(p, id, key), r);
  }
}
