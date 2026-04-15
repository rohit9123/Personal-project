package com.example.inventoryservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    @GetMapping("/inventory/{productId}")
    public String checkInventory(@PathVariable String productId) {
        logger.info("Checking inventory for product: {}", productId);
        return "Product " + productId + " is IN STOCK";
    }
}
