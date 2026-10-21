package com.smartprocure.logistics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration
@Profile({"demo", "test"})
@EnableScheduling
@ConditionalOnProperty(
    prefix = "app.mock",
    name = "worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FulfillmentSchedule {
  private final ShipmentWorker worker;

  public FulfillmentSchedule(ShipmentWorker w) {
    worker = w;
  }

  @Scheduled(fixedDelayString = "${app.mock.shipment-worker-delay-ms:750}", initialDelay = 2500)
  void tick() {
    worker.runOnce();
  }
}
