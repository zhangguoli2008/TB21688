package com.smartprocure.provider;

import java.util.*;

public interface ShippingProviderAdapter extends ProviderAdapter {
  record Source(
      String eventId,
      String externalShipmentId,
      long sourceVersion,
      int qty,
      String carrierCode,
      String trackingNumber,
      String sourceState,
      String behavior) {}

  record Request(
      UUID shipmentId,
      UUID salesOrderId,
      UUID salesOrderItemId,
      int qty,
      long trackingVersion,
      String carrierCode,
      String trackingNumber,
      String providerKey) {}

  record Ack(Request request, String platformAck) {}

  Source source(UUID tenant, UUID purchase, String external);

  void submit(UUID tenant, Request request);

  Optional<Ack> query(UUID tenant, Request request);
}
