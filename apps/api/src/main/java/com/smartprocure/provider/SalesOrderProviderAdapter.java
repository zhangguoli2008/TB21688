package com.smartprocure.provider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal authority lookup contract. No external official API path is claimed. */
public interface SalesOrderProviderAdapter extends ProviderAdapter {
    record Item(String externalItemId,String externalSkuId,String title,int qty,long paidAllocatedFen,int cancelledQty,int fulfilledQty) { }
    record Snapshot(String externalOrderId,String sourceStatus,long sourceVersion,String currency,long paidAmountFen,Instant paidAt,Instant promisedShipAt,Instant sourceUpdatedAt,List<Item> items) { }
    Snapshot authoritativeOrder(UUID tenantId,UUID storeId,String externalOrderId);
}
