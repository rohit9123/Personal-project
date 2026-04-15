package com.example.inventoryservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventory Controller — includes the server port in the response
 * so we can verify which instance handled the request (load balancing demo).
 */
@RestController
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    @Value("${server.port}")
    private int serverPort;

    @GetMapping("/inventory/{productId}")
    public String checkInventory(@PathVariable String productId) {
        logger.info("Checking inventory for product: {} (port: {})", productId, serverPort);

        // Include port in response so we can see which instance handled the request
        return "Product " + productId + " is IN STOCK (served by port " + serverPort + ")";
    }
}
