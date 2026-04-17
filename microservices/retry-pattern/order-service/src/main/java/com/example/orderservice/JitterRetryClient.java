package com.example.orderservice;

import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Exponential backoff + jitter retry strategy — wait is randomised within
 * an exponentially growing window.
 *
 * Timeline (failTimes=2, maxAttempts=4, base=1s, multiplier=2, jitter=±50%):
 *
 *   Attempt 1 → HTTP 500 → wait ~0.5–1.5s   (1s ± 50%)
 *   Attempt 2 → HTTP 500 → wait ~1.0–3.0s   (2s ± 50%)
 *   Attempt 3 → HTTP 200 ✓
 *
 * Why jitter?
 *   Without it, 500 clients that all got the same error at time T all retry
 *   at T+1s, then T+3s, then T+7s — perfectly synchronised bursts hit a
 *   recovering service and can push it back into failure ("thundering herd").
 *
 *   Jitter spreads those 500 retries across a time window instead of stacking
 *   them at a single instant. The recovering service sees a smooth ramp-up
 *   rather than a spike.
 *
 * randomized-wait-factor=0.5 means: actual wait = exponential_wait × (1 ± 0.5)
 *   → a uniformly random value in [wait×0.5, wait×1.5]
 *
 * Config in application.yml → resilience4j.retry.instances.jitter-retry
 */
@Service
public class JitterRetryClient {

    private static final Logger logger = LoggerFactory.getLogger(JitterRetryClient.class);

    private final RestTemplate restTemplate;
    private final String inventoryServiceUrl;

    public JitterRetryClient(RestTemplate restTemplate,
                             @Value("${inventory.service.url}") String inventoryServiceUrl) {
        this.restTemplate = restTemplate;
        this.inventoryServiceUrl = inventoryServiceUrl;
    }

    @Retry(name = "jitter-retry", fallbackMethod = "fallback")
    public String checkInventory(String productId, int failTimes) {
        logger.info("[jitter-retry] calling inventory-service for product {} (failTimes={})",
                productId, failTimes);
        String url = inventoryServiceUrl + "/inventory/" + productId + "?failTimes=" + failTimes;
        return restTemplate.getForObject(url, String.class);
    }

    public String fallback(String productId, int failTimes, Throwable t) {
        logger.warn("[jitter-retry] all retries exhausted for {} — returning degraded response. Cause: {}",
                productId, t.getMessage());
        return "Inventory UNAVAILABLE for " + productId + " after jitter retries (degraded response)";
    }
}
