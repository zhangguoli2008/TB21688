package com.smartprocure.provider;

import java.util.Set;
import java.util.UUID;

/** Internal synthetic payment boundary. TODO: PLATFORM_APPROVAL before any real channel exists. */
public interface PaymentProviderAdapter extends ProviderAdapter {
    record Request(UUID purchaseId,UUID supplierAccountId,String externalOrderId,String providerKey,long amountFen,String currency) { }
    record Snapshot(UUID purchaseId,UUID supplierAccountId,String externalOrderId,long amountFen,String currency,String state,String paymentReference) { }
    Snapshot purchase(UUID tenant,Request request);
    void pay(UUID tenant,Request request);
    Snapshot query(UUID tenant,Request request);
    Snapshot simulateReceipt(UUID tenant,Request request,String outcome);
}
