package com.smartprocure.sales;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@Profile({"demo","test"})
@EnableScheduling
@ConditionalOnProperty(name="app.mock.worker-enabled",havingValue="true",matchIfMissing=true)
public class MockWorkerSchedule {
    private final SalesEventWorker worker;
    public MockWorkerSchedule(SalesEventWorker worker) { this.worker=worker; }
    @Scheduled(fixedDelayString="${app.mock.worker-delay-ms:500}",initialDelay=1500)
    void tick() { worker.runOnce(); }
}
