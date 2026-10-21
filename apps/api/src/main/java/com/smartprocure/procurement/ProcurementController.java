package com.smartprocure.procurement;

import com.smartprocure.common.ApiEnvelope;
import com.smartprocure.common.M1Support;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1")
public class ProcurementController {
    private final ProcurementService service;
    private final ProcurementRuleEngine engine;
    public ProcurementController(ProcurementService service,ProcurementRuleEngine engine) { this.service=service;this.engine=engine; }
    @PostMapping("/purchase-intents/evaluate")
    ApiEnvelope<ProcurementRuleEngine.Evaluation> evaluate(@AuthenticationPrincipal TenantPrincipal p,@Valid @RequestBody ProcurementService.Evaluate body,HttpServletRequest r) { return ApiEnvelope.of(engine.evaluate(p,body.salesOrderItemId()),r); }
    @PostMapping("/purchase-intents") @ResponseStatus(HttpStatus.ACCEPTED)
    ApiEnvelope<ProcurementService.Intent> create(@AuthenticationPrincipal TenantPrincipal p,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ProcurementService.Create body,HttpServletRequest r) { return ApiEnvelope.of(service.create(p,key,body),r); }
    @GetMapping("/purchase-intents")
    ApiEnvelope<M1Support.Page<ProcurementService.Intent>> intents(@AuthenticationPrincipal TenantPrincipal p,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(required=false) UUID storeId,@RequestParam(required=false) String state,HttpServletRequest r) { return ApiEnvelope.of(service.intents(p,limit,offset,storeId,state),r); }
    @GetMapping("/purchase-intents/{intentId}")
    ApiEnvelope<ProcurementService.Intent> intent(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID intentId,HttpServletRequest r) { return ApiEnvelope.of(service.intent(p,intentId),r); }
    @GetMapping("/purchases")
    ApiEnvelope<M1Support.Page<ProcurementService.Purchase>> purchases(@AuthenticationPrincipal TenantPrincipal p,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(required=false) UUID storeId,@RequestParam(required=false) String state,HttpServletRequest r) { return ApiEnvelope.of(service.purchases(p,limit,offset,storeId,state),r); }
    @GetMapping("/purchases/{purchaseId}")
    ApiEnvelope<ProcurementService.Purchase> purchase(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID purchaseId,HttpServletRequest r) { return ApiEnvelope.of(service.purchase(p,purchaseId),r); }
}
