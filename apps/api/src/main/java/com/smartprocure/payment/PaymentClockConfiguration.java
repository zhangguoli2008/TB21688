package com.smartprocure.payment;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PaymentClockConfiguration {
    @Bean Clock paymentClock() { return Clock.systemUTC(); }
}
