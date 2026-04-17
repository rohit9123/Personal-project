package com.example.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Order Service — demonstrates Resilience4j Circuit Breaker.
 *
 * Endpoints:
 *   GET /order/{productId}     → calls inventory-service with a circuit breaker;
 *                                returns fallback when the circuit is OPEN
 *   GET /order/circuit-state   → shows current circuit breaker state (CLOSED / OPEN / HALF_OPEN)
 *
 * Circuit breaker config (application.yml):
 *   sliding-window-size = 5 calls
 *   failure-rate-threshold = 60%  →  open after 3 failures in the last 5 calls
 *   wait-duration-in-open-state = 10s
 *   permitted-calls-in-half-open = 2
 */
@SpringBootApplication
public class OrderServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApp.class, args);
    }
}
