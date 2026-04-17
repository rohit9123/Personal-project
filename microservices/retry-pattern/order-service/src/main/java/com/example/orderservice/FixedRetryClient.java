package com.example.orderservice;

import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Fixed retry strategy — waits a constant 500 ms between every attempt.
 *
 * Timeline (failTimes=2, maxAttempts=4):
 *
 *   Attempt 1 → HTTP 500 → wait 500ms
 *   Attempt 2 → HTTP 500 → wait 500ms
 *   Attempt 3 → HTTP 200 ✓
 *
 * Good for: predictable retry cadence when you know the dependency recovers quickly.
 * Bad for:  high-traffic scenarios — all callers retry at exactly the same interval,
 *           which creates synchronized load spikes on a recovering service.
 *
 * Config in application.yml → resilience4j.retry.instances.fixed-retry
 */
@Service
public class FixedRetryClient {

    private static final Logger logger = LoggerFactory.getLogger(FixedRetryClient.class);

    private final RestTemplate restTemplate;
    private final String inventoryServiceUrl;

    public FixedRetryClient(RestTemplate restTemplate,
                            @Value("${inventory.service.url}") String inventoryServiceUrl) {
        this.restTemplate = restTemplate;
        this.inventoryServiceUrl = inventoryServiceUrl;
    }

    @Retry(name = "fixed-retry", fallbackMethod = "fallback")
    public String checkInventory(String productId, int failTimes) {
        logger.info("[fixed-retry] calling inventory-service for product {} (failTimes={})",
                productId, failTimes);
        String url = inventoryServiceUrl + "/inventory/" + productId + "?failTimes=" + failTimes;
        return restTemplate.getForObject(url, String.class);
    }

    /**
     * Activated when all retry attempts are exhausted.
     * Signature must match the protected method plus a Throwable at the end.
     */
    public String fallback(String productId, int failTimes, Throwable t) {
        logger.warn("[fixed-retry] all retries exhausted for {} — returning degraded response. Cause: {}",
                productId, t.getMessage());
        return "Inventory UNAVAILABLE for " + productId + " after fixed retries (degraded response)";
    }
}
