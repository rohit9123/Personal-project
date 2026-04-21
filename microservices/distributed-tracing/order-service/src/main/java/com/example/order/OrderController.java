package com.example.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Entry-point controller for the order-service.
 *
 * <p>order-service is the <em>upstream</em> service: it receives the initial
 * HTTP request, which causes Micrometer Tracing to create the <strong>root
 * span</strong> for the entire distributed trace. When this controller calls
 * inventory-service via {@link RestTemplate}, the
 * {@code TracingClientHttpRequestInterceptor} (registered automatically by
 * Spring Boot's {@code RestTemplateBuilder}) injects the W3C
 * {@code traceparent} header into the outbound request. inventory-service
 * reads that header and creates a <strong>child span</strong> under the same
 * traceId, so both spans appear linked in Grafana Tempo.
 */
@RestController
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final RestTemplate restTemplate;

    public OrderController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/orders/{orderId}")
    public String placeOrder(@PathVariable String orderId) {
        log.info("Processing order orderId={}", orderId);
        // restTemplate carries the traceparent header automatically —
        // inventory-service will create a child span under this trace.
        // NOTE: URL is hardcoded to localhost for demo clarity.
        // In a multi-environment setup, extract this to a @Value property
        // in application.yml (e.g. inventory.base-url=http://localhost:9082).
        // This will also fail if order-service is containerized — services
        // must both run on the host for localhost resolution to work.
        String stock = restTemplate.getForObject(
            "http://localhost:9082/inventory/" + orderId, String.class);
        return "Order #" + orderId + " confirmed. Stock: " + stock;
    }
}
