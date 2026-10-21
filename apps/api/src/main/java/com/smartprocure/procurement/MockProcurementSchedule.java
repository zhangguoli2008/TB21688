package com.smartprocure.procurement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration @Profile({"demo","test"}) @EnableScheduling
@ConditionalOnProperty(prefix="app.mock",name={"worker-enabled","procurement-worker-enabled"},havingValue="true",matchIfMissing=true)
public class MockProcurementSchedule {
    private final ProcurementWorker worker;
    public MockProcurementSchedule(ProcurementWorker worker) { this.worker=worker; }
    @Scheduled(fixedDelayString="${app.mock.procurement-worker-delay-ms:750}",initialDelay=2000)
    void tick() { worker.runOnce(); }
}
