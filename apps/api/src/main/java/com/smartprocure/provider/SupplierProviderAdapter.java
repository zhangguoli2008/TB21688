package com.smartprocure.provider;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Internal supplier boundary. All values and references below are synthetic Mock data. */
public interface SupplierProviderAdapter extends ProviderAdapter {
    record Quote(UUID offerId,UUID supplierAccountId,long unitPriceFen,long shippingFen,long feeFen,
                 int stockQty,int minOrderQty,String currency,Set<String> regions,
                 Set<String> ciphertextPlatforms,Instant quotedAt,Instant expiresAt,long offerVersion) { }
    record CreateRequest(UUID supplierAccountId,UUID offerId,String clientReference,int qty,long totalFen,
                         String currency,long offerVersion,Instant quoteExpiresAt) { }
    record Purchase(String externalOrderId,String clientReference,UUID supplierAccountId,long totalFen,
                    String currency,String state) { }
    Quote quote(UUID tenantId,UUID offerId,int qty);
    Purchase create(UUID tenantId,CreateRequest request);
    Optional<Purchase> query(UUID tenantId,UUID supplierAccountId,String clientReference);
}
