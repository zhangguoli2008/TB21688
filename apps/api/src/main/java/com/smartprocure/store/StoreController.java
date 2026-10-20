package com.smartprocure.store;
import com.smartprocure.common.ApiEnvelope;
import com.smartprocure.common.M1Support;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stores")
public class StoreController {
    private final StoreService stores;
    public StoreController(StoreService stores) { this.stores=stores; }
    @GetMapping ApiEnvelope<M1Support.Page<StoreService.Store>> list(@AuthenticationPrincipal TenantPrincipal p,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset,HttpServletRequest r) { return ApiEnvelope.of(stores.list(p,limit,offset),r); }
    @GetMapping("/{storeId}") ApiEnvelope<StoreService.Store> get(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID storeId,HttpServletRequest r) { return ApiEnvelope.of(stores.get(p,storeId),r); }
    @PostMapping ApiEnvelope<StoreService.Store> create(@AuthenticationPrincipal TenantPrincipal p,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody StoreService.Create body,HttpServletRequest r) { return ApiEnvelope.of(stores.create(p,key,body),r); }
    @PatchMapping("/{storeId}") ApiEnvelope<StoreService.Store> update(@AuthenticationPrincipal TenantPrincipal p,@PathVariable UUID storeId,@Valid @RequestBody StoreService.Update body,HttpServletRequest r) { return ApiEnvelope.of(stores.update(p,storeId,body),r); }
}
