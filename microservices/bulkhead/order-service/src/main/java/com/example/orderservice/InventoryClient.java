package com.example.orderservice;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Calls inventory-service, protected by a SemaphoreBulkhead.
 *
 * TYPE.SEMAPHORE (the default) uses a counting semaphore — no extra threads.
 * The caller's own thread acquires a permit before proceeding.
 * If max-concurrent-calls permits are already taken and max-wait-duration=0,
 * Resilience4j throws BulkheadFullException immediately.
 *
 * Lighter than ThreadPoolBulkhead, but the caller thread is blocked
 * for the duration of the call.
 */
@Service
public class InventoryClient {

    private static final Logger logger = LoggerFactory.getLogger(InventoryClient.class);

    private final RestTemplate restTemplate;
    private final String inventoryServiceUrl;

    public InventoryClient(RestTemplate restTemplate,
                           @Value("${inventory.service.url}") String inventoryServiceUrl) {
        this.restTemplate = restTemplate;
        this.inventoryServiceUrl = inventoryServiceUrl;
    }

    @Bulkhead(name = "inventory", type = Bulkhead.Type.SEMAPHORE, fallbackMethod = "inventoryFallback")
    public String checkInventory(String productId) {
        logger.info("Calling inventory-service for product {}", productId);
        String url = inventoryServiceUrl + "/inventory/" + productId;
        return restTemplate.getForObject(url, String.class);
    }

    /**
     * Fallback invoked when the semaphore has no permits left.
     */
    public String inventoryFallback(String productId, Throwable t) {
        logger.warn("Inventory bulkhead FULL for product {} — rejecting. Reason: {}", productId, t.getMessage());
        return "Inventory check SKIPPED (bulkhead full) for product " + productId;
    }
}
