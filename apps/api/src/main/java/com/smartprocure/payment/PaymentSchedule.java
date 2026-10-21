package com.smartprocure.payment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
@Configuration @Profile({"demo","test"}) @EnableScheduling
@ConditionalOnProperty(prefix="app.mock",name={"worker-enabled","payment-worker-enabled"},havingValue="true",matchIfMissing=true)
public class PaymentSchedule {
    private final PaymentWorker worker;public PaymentSchedule(PaymentWorker worker){this.worker=worker;}
    @Scheduled(fixedDelayString="${app.mock.payment-worker-delay-ms:750}",initialDelay=2500) void tick(){worker.runOnce();}
    @Scheduled(fixedDelayString="${app.mock.payment-reconcile-delay-ms:300000}",initialDelay=300000) void reconcile(){worker.reconcileOverdue();}
}
