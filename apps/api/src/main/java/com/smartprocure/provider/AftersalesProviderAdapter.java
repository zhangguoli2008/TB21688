package com.smartprocure.provider;

import java.util.*;

public interface AftersalesProviderAdapter extends ProviderAdapter {
  record Source(
      String eventId,
      String sourceCaseId,
      String type,
      String sourceState,
      long sourceVersion,
      UUID salesOrderItemId,
      int qty,
      long requestedRefundFen) {}

  record Supplier(
      String eventId,
      UUID purchaseOrderId,
      long sourceVersion,
      String state,
      long refundAmountFen) {}

  Source source(UUID tenant, UUID order, String external);

  Supplier supplier(UUID tenant, UUID aftersale, UUID purchase);
}
