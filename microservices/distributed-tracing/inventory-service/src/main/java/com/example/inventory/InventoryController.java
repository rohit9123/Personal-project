package com.example.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    @GetMapping("/inventory/{itemId}")
    public String getStock(@PathVariable String itemId) {
        // Micrometer auto-adds traceId + spanId to MDC — visible in log output
        log.info("Checking stock for itemId={}", itemId);
        return "Item " + itemId + ": 42 units in stock";
    }
}
