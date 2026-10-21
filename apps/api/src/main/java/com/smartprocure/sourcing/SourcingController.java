package com.smartprocure.sourcing;

import com.smartprocure.common.ApiEnvelope;
import com.smartprocure.procurement.ProcurementPolicyService;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SourcingController {
    private final SourcingService sourcing;
    private final ProcurementPolicyService policies;
    public SourcingController(SourcingService sourcing,ProcurementPolicyService policies) { this.sourcing=sourcing;this.policies=policies; }
    @GetMapping("/supplier-offers") Object offers(@AuthenticationPrincipal TenantPrincipal p,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset,HttpServletRequest request) { return ApiEnvelope.of(sourcing.offers(p,limit,offset),request); }
    @PatchMapping("/supplier-offers/{id}/mock-config") Object configure(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID id,@Valid @RequestBody SourcingService.OfferConfig input,HttpServletRequest request) { return ApiEnvelope.of(sourcing.configure(p,id,input),request); }
    @PostMapping("/supplier-offers/{id}/quotes") Object quote(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID id,@Valid @RequestBody SourcingService.QuoteRequest input,HttpServletRequest request) { return ApiEnvelope.of(sourcing.quote(p,id,input),request); }
    @GetMapping("/sku-mappings") Object mappings(@AuthenticationPrincipal TenantPrincipal p,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(required=false) UUID storeId,@RequestParam(required=false) String salesSkuId,@RequestParam(required=false) SourcingService.MappingStatus status,HttpServletRequest request) { return ApiEnvelope.of(sourcing.mappings(p,limit,offset,storeId,salesSkuId,status),request); }
    @PostMapping("/sku-mappings") Object createMapping(@AuthenticationPrincipal TenantPrincipal p,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody SourcingService.MappingCreate input,HttpServletRequest request) { return ApiEnvelope.of(sourcing.createMapping(p,key,input),request); }
    @PatchMapping("/sku-mappings/{id}") Object updateMapping(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID id,@Valid @RequestBody SourcingService.MappingUpdate input,HttpServletRequest request) { return ApiEnvelope.of(sourcing.updateMapping(p,id,input),request); }
    @GetMapping("/procurement-policy") Object tenantPolicy(@AuthenticationPrincipal TenantPrincipal p,HttpServletRequest request) { return ApiEnvelope.of(policies.get(p,null),request); }
    @PutMapping("/procurement-policy") Object updateTenantPolicy(@AuthenticationPrincipal TenantPrincipal p,@Valid @RequestBody ProcurementPolicyService.Update input,HttpServletRequest request) { return ApiEnvelope.of(policies.update(p,null,input),request); }
    @GetMapping("/procurement-policies/{storeId}") Object storePolicy(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID storeId,HttpServletRequest request) { return ApiEnvelope.of(policies.get(p,storeId),request); }
    @PutMapping("/procurement-policies/{storeId}") Object updateStorePolicy(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID storeId,@Valid @RequestBody ProcurementPolicyService.Update input,HttpServletRequest request) { return ApiEnvelope.of(policies.update(p,storeId,input),request); }
}
