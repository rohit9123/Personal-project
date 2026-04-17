package com.example.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Order Service — demonstrates three Resilience4j retry strategies:
 *
 *   GET /order/{id}/fixed        → fixed wait between each attempt (500 ms)
 *   GET /order/{id}/exponential  → wait doubles each attempt (1s → 2s → 4s)
 *   GET /order/{id}/jitter       → exponential + random jitter (avoids thundering herd)
 *
 * All three call the same inventory-service endpoint which is configured to
 * fail a controllable number of times before succeeding.
 *
 * Pass ?failTimes=N (default 2) to set how many transient failures to inject.
 * Pass ?failTimes=10 to exhaust all retries and trigger the fallback.
 */
@SpringBootApplication
public class OrderServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApp.class, args);
    }
}
