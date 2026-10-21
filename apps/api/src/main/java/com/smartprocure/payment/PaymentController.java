package com.smartprocure.payment;
import com.smartprocure.common.*;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/api/v1")
public class PaymentController {
    private final PaymentPolicyService policies;private final PaymentService service;
    public PaymentController(PaymentPolicyService policies,PaymentService service){this.policies=policies;this.service=service;}
    @GetMapping("/payment-policy") ApiEnvelope<PaymentPolicyService.Policy> policy(@AuthenticationPrincipal TenantPrincipal p,HttpServletRequest r){return ApiEnvelope.of(policies.get(p),r);}
    @PutMapping("/payment-policy") ApiEnvelope<PaymentPolicyService.Policy> policy(@AuthenticationPrincipal TenantPrincipal p,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PaymentPolicyService.Update u,HttpServletRequest r){return ApiEnvelope.of(policies.update(p,key,u),r);}
    @GetMapping("/payment-mandates") ApiEnvelope<M1Support.Page<PaymentPolicyService.Mandate>> mandates(@AuthenticationPrincipal TenantPrincipal p,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset,HttpServletRequest r){return ApiEnvelope.of(policies.mandates(p,limit,offset),r);}
    @PatchMapping("/payment-mandates/{mandateId}/mock-state") ApiEnvelope<PaymentPolicyService.Mandate> mandate(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID mandateId,@Valid @RequestBody PaymentPolicyService.MandateUpdate u,HttpServletRequest r){return ApiEnvelope.of(policies.updateMandate(p,mandateId,u),r);}
    @GetMapping("/payment-queue") ApiEnvelope<M1Support.Page<PaymentService.View>> queue(@AuthenticationPrincipal TenantPrincipal p,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(required=false) UUID storeId,@RequestParam(required=false) String state,HttpServletRequest r){return ApiEnvelope.of(service.queue(p,limit,offset,storeId,state),r);}
    @GetMapping("/purchases/{purchaseId}/payment-status") ApiEnvelope<PaymentService.View> status(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID purchaseId,HttpServletRequest r){return ApiEnvelope.of(service.get(p,purchaseId),r);}
    @PostMapping("/purchases/{purchaseId}/payments") @ResponseStatus(HttpStatus.ACCEPTED) ApiEnvelope<PaymentService.View> pay(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID purchaseId,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ApiEnvelope.of(service.request(p,purchaseId,key),r);}
    @PostMapping("/purchases/{purchaseId}/checkout-entry") ApiEnvelope<PaymentService.Entry> entry(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID purchaseId,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ApiEnvelope.of(service.checkoutEntry(p,purchaseId,key),r);}
    @GetMapping("/payment-attempts/{attemptId}/mock-checkout") ApiEnvelope<PaymentService.Checkout> checkout(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID attemptId,HttpServletRequest r){return ApiEnvelope.of(service.checkout(p,attemptId),r);}
    @PostMapping("/mock/payment-attempts/{attemptId}/receipt") @ResponseStatus(HttpStatus.ACCEPTED) ApiEnvelope<PaymentService.View> receipt(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID attemptId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PaymentService.Receipt u,HttpServletRequest r){return ApiEnvelope.of(service.receipt(p,attemptId,key,u),r);}
    @PostMapping("/purchases/{purchaseId}/payment-reconcile") @ResponseStatus(HttpStatus.ACCEPTED) ApiEnvelope<PaymentService.View> reconcile(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID purchaseId,@RequestHeader("Idempotency-Key") String key,HttpServletRequest r){return ApiEnvelope.of(service.reconcile(p,purchaseId,key),r);}
    @GetMapping("/mock/purchases/{purchaseId}/payment-config") ApiEnvelope<PaymentService.Config> config(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID purchaseId,HttpServletRequest r){return ApiEnvelope.of(service.config(p,purchaseId),r);}
    @PatchMapping("/mock/purchases/{purchaseId}/payment-config") ApiEnvelope<PaymentService.Config> config(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID purchaseId,@Valid @RequestBody PaymentService.ConfigUpdate u,HttpServletRequest r){return ApiEnvelope.of(service.configure(p,purchaseId,u),r);}
    @GetMapping("/payment-reconciliations") ApiEnvelope<M1Support.Page<PaymentService.Reconciliation>> reconciliations(@AuthenticationPrincipal TenantPrincipal p,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(required=false) UUID storeId,HttpServletRequest r){return ApiEnvelope.of(service.reconciliations(p,limit,offset,storeId),r);}
}
