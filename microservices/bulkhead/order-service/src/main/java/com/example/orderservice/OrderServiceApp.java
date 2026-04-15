package com.example.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Order Service — demonstrates the Bulkhead pattern using Resilience4j.
 *
 * Two downstream dependencies:
 *   - payment-service  → protected by a ThreadPoolBulkhead (separate thread pool)
 *   - inventory-service → protected by a SemaphoreBulkhead (concurrent call limit)
 *
 * Key idea: a slow/failing payment-service cannot exhaust all threads and
 * block inventory calls, because each dependency has its own isolated resource pool.
 */
@SpringBootApplication
public class OrderServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApp.class, args);
    }
}
