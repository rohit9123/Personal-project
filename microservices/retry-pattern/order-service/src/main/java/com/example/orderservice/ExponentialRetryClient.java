package com.example.orderservice;

import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Exponential backoff retry strategy — wait doubles after each failed attempt.
 *
 * Timeline (failTimes=2, maxAttempts=4, base=1s, multiplier=2):
 *
 *   Attempt 1 → HTTP 500 → wait 1s
 *   Attempt 2 → HTTP 500 → wait 2s
 *   Attempt 3 → HTTP 200 ✓
 *
 *   (If all fail: Attempt 4 would wait 4s before exhausting)
 *
 * Why exponential?
 *   Fixed retry hammers the downstream every 500ms regardless of how overloaded
 *   it is. Exponential backoff gives the dependency progressively more time to
 *   recover — reduces pressure on a struggling service with each retry.
 *
 * Downside: still synchronised across callers (thundering herd) — see JitterRetryClient.
 *
 * Config in application.yml → resilience4j.retry.instances.exponential-retry
 */
@Service
public class ExponentialRetryClient {

    private static final Logger logger = LoggerFactory.getLogger(ExponentialRetryClient.class);

    private final RestTemplate restTemplate;
    private final String inventoryServiceUrl;

    public ExponentialRetryClient(RestTemplate restTemplate,
                                  @Value("${inventory.service.url}") String inventoryServiceUrl) {
        this.restTemplate = restTemplate;
        this.inventoryServiceUrl = inventoryServiceUrl;
    }

    @Retry(name = "exponential-retry", fallbackMethod = "fallback")
    public String checkInventory(String productId, int failTimes) {
        logger.info("[exponential-retry] calling inventory-service for product {} (failTimes={})",
                productId, failTimes);
        String url = inventoryServiceUrl + "/inventory/" + productId + "?failTimes=" + failTimes;
        return restTemplate.getForObject(url, String.class);
    }

    public String fallback(String productId, int failTimes, Throwable t) {
        logger.warn("[exponential-retry] all retries exhausted for {} — returning degraded response. Cause: {}",
                productId, t.getMessage());
        return "Inventory UNAVAILABLE for " + productId + " after exponential retries (degraded response)";
    }
}
