package com.smartprocure.reporting;

import com.smartprocure.common.*;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportingController {
  private final ReportingService service;

  public ReportingController(ReportingService service) {
    this.service = service;
  }

  @GetMapping("/overview")
  ApiEnvelope<Map<String, Object>> overview(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestParam(required = false) UUID storeId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      HttpServletRequest r) {
    return ApiEnvelope.of(service.overview(p, storeId, from, to), r);
  }

  @GetMapping("/orders")
  ApiEnvelope<M1Support.Page<Map<String, Object>>> orders(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestParam(required = false) UUID storeId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest r) {
    return ApiEnvelope.of(service.orders(p, storeId, from, to, limit, offset), r);
  }

  @PostMapping("/refresh")
  @ResponseStatus(HttpStatus.ACCEPTED)
  ApiEnvelope<ReportingService.Refresh> refresh(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    return ApiEnvelope.of(service.refresh(p, key), r);
  }
}
