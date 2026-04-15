package com.example.orderservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * Order Controller — calls both downstream services per request.
 *
 * Demo endpoints:
 *
 *   GET /order/{id}              → normal request (both calls fast)
 *   GET /order/{id}?slowPayment=true → payment-service sleeps 5s
 *
 * To see the bulkhead in action:
 *   1. Fire several concurrent requests with ?slowPayment=true
 *   2. The payment ThreadPoolBulkhead (max 2 threads + queue 1) fills up
 *   3. Requests beyond that get the fallback response immediately
 *   4. Meanwhile, inventory calls still succeed — isolation is working
 */
@RestController
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final PaymentClient paymentClient;
    private final InventoryClient inventoryClient;

    public OrderController(PaymentClient paymentClient, InventoryClient inventoryClient) {
        this.paymentClient = paymentClient;
        this.inventoryClient = inventoryClient;
    }

    @GetMapping("/order/{id}")
    public String placeOrder(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean slowPayment) throws Exception {

        logger.info("Placing order {} (slowPayment={})", id, slowPayment);

        // Inventory check — SemaphoreBulkhead, synchronous
        String inventoryResult = inventoryClient.checkInventory(id);

        // Payment — ThreadPoolBulkhead, returns CompletableFuture
        CompletableFuture<String> paymentFuture = paymentClient.processPayment(id, slowPayment);
        String paymentResult = paymentFuture.get(); // block until done (or fallback returned)

        return "Order " + id + " | " + inventoryResult + " | " + paymentResult;
    }
}
