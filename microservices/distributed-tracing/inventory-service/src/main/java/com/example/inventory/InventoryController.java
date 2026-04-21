package com.example.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventory endpoint — the downstream service in the distributed-tracing demo.
 *
 * <p>Instrumented via {@code micrometer-tracing-bridge-otel}: Micrometer's {@code Tracer}
 * creates a child span for each inbound request, adding it to the active trace propagated
 * by order-service via the W3C {@code traceparent} header. The span's traceId and spanId
 * are injected into MDC, visible in log output via the configured console pattern.
 */
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
