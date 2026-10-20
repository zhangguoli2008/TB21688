package com.smartprocure.sales;
import com.smartprocure.common.ApiEnvelope;
import com.smartprocure.common.M1Support;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SalesController {
    private final SalesOrderService orders;
    private final MockScenarioService scenarios;
    private final MockWebhookService webhooks;
    public SalesController(SalesOrderService orders,MockScenarioService scenarios,MockWebhookService webhooks) { this.orders=orders;this.scenarios=scenarios;this.webhooks=webhooks; }
    @GetMapping("/orders") ApiEnvelope<M1Support.Page<SalesOrderService.Order>> list(@AuthenticationPrincipal TenantPrincipal p,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(required=false) UUID storeId,@RequestParam(required=false) String sourceStatus,@RequestParam(required=false) String q,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,HttpServletRequest r) { return ApiEnvelope.of(orders.list(p,limit,offset,storeId,sourceStatus,q,from,to),r); }
    @GetMapping("/orders/{orderId}") ApiEnvelope<Map<String,Object>> get(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID orderId,HttpServletRequest r) { return ApiEnvelope.of(orders.detail(p,orderId),r); }
    @PostMapping("/mock/stores/{storeId}/scenarios") @ResponseStatus(HttpStatus.ACCEPTED)
    ApiEnvelope<MockScenarioService.Result> simulate(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID storeId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MockScenarioService.Input body,HttpServletRequest r) { return ApiEnvelope.of(scenarios.generate(p,storeId,key,body),r); }
    @PostMapping("/webhooks/mock/stores/{integrationId}")
    ApiEnvelope<MockWebhookService.Receipt> callback(@PathVariable UUID integrationId,@RequestHeader("X-Mock-Timestamp") String timestamp,@RequestHeader("X-Mock-Nonce") String nonce,@RequestHeader("X-Mock-Signature") String signature,@RequestBody String raw,HttpServletRequest r) { return ApiEnvelope.of(webhooks.receive(integrationId,timestamp,nonce,signature,raw),r); }
}
