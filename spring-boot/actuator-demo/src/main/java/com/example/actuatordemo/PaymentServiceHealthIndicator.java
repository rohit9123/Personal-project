package com.example.actuatordemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom HealthIndicator — shows up under /actuator/health as "paymentService".
 *
 * Spring Boot names the indicator by stripping "HealthIndicator" from the class name
 * and lowercasing the first letter: PaymentServiceHealthIndicator → "paymentService".
 *
 * Use POST /payment/break and POST /payment/fix (see OrderController) to toggle
 * the simulated state and watch /actuator/health change in real time.
 */
@Component
public class PaymentServiceHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceHealthIndicator.class);

    // shared state toggled by the controller so you can demo UP ↔ DOWN live
    static final AtomicBoolean healthy = new AtomicBoolean(true);

    @Override
    public Health health() {
        if (healthy.get()) {
            log.debug("PaymentService health check: UP");
            return Health.up()
                    .withDetail("service",      "payment-service")
                    .withDetail("responseTime", "8ms")
                    .withDetail("status",       "reachable")
                    .build();
        } else {
            log.warn("PaymentService health check: DOWN");
            return Health.down()
                    .withDetail("service", "payment-service")
                    .withDetail("error",   "connection refused — simulated outage")
                    .build();
        }
    }
}
