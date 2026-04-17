package com.example.orderservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point for all three retry strategy demos.
 *
 * Endpoints:
 *
 *   GET /order/{id}/fixed           → fixed 500ms wait between retries
 *   GET /order/{id}/exponential     → wait doubles each attempt (1s → 2s → 4s)
 *   GET /order/{id}/jitter          → exponential + random jitter (±50%)
 *
 * Query params:
 *   ?failTimes=N   How many times inventory-service should fail before succeeding.
 *                  Default: 2 (3rd attempt succeeds — shows retry working).
 *                  Set to 10 to exhaust all retries and trigger the fallback.
 *
 * Examples:
 *   curl http://localhost:8080/order/item-1/fixed
 *   curl http://localhost:8080/order/item-2/exponential
 *   curl http://localhost:8080/order/item-3/jitter?failTimes=10
 */
@RestController
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final FixedRetryClient fixedRetryClient;
    private final ExponentialRetryClient exponentialRetryClient;
    private final JitterRetryClient jitterRetryClient;

    public OrderController(FixedRetryClient fixedRetryClient,
                           ExponentialRetryClient exponentialRetryClient,
                           JitterRetryClient jitterRetryClient) {
        this.fixedRetryClient = fixedRetryClient;
        this.exponentialRetryClient = exponentialRetryClient;
        this.jitterRetryClient = jitterRetryClient;
    }

    /**
     * Fixed retry — constant wait between attempts.
     * Config: maxAttempts=4, waitDuration=500ms
     */
    @GetMapping("/order/{id}/fixed")
    public String fixedRetry(
            @PathVariable String id,
            @RequestParam(defaultValue = "2") int failTimes) {

        logger.info("=== FIXED RETRY demo: product={}, failTimes={} ===", id, failTimes);
        String inventoryResult = fixedRetryClient.checkInventory(id, failTimes);
        return "Order " + id + " [fixed-retry] | " + inventoryResult;
    }

    /**
     * Exponential backoff — wait time doubles after each failure.
     * Config: maxAttempts=4, waitDuration=1s, multiplier=2
     */
    @GetMapping("/order/{id}/exponential")
    public String exponentialRetry(
            @PathVariable String id,
            @RequestParam(defaultValue = "2") int failTimes) {

        logger.info("=== EXPONENTIAL RETRY demo: product={}, failTimes={} ===", id, failTimes);
        String inventoryResult = exponentialRetryClient.checkInventory(id, failTimes);
        return "Order " + id + " [exponential-retry] | " + inventoryResult;
    }

    /**
     * Exponential backoff + jitter — random wait within an exponential window.
     * Config: maxAttempts=4, waitDuration=1s, multiplier=2, randomizedWaitFactor=0.5
     */
    @GetMapping("/order/{id}/jitter")
    public String jitterRetry(
            @PathVariable String id,
            @RequestParam(defaultValue = "2") int failTimes) {

        logger.info("=== JITTER RETRY demo: product={}, failTimes={} ===", id, failTimes);
        String inventoryResult = jitterRetryClient.checkInventory(id, failTimes);
        return "Order " + id + " [jitter-retry] | " + inventoryResult;
    }
}
