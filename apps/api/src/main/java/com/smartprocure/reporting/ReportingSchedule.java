package com.smartprocure.reporting;

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
public class ReportingSchedule {
  private final ReportProjectionWorker worker;

  public ReportingSchedule(ReportProjectionWorker worker) {
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${app.mock.report-worker-delay-ms:1000}", initialDelay = 3000)
  void tick() {
    worker.runOnce();
  }
}
