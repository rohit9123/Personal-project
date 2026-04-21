package com.example.orderservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order Controller — a simple downstream endpoint behind the API Gateway.
 *
 * The gateway routes any request arriving at /api/orders/** to this service,
 * stripping the /api prefix before forwarding. So:
 *
 *   Client:  GET http://localhost:8080/api/orders/42
 *   Gateway: forwards to → http://order-service/orders/42  (resolved via Eureka)
 *   Here:    handles GET /orders/42
 */
@RestController
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    @GetMapping("/orders/{id}")
    public String getOrder(@PathVariable String id) {
        logger.info("Handling order request for id={}", id);
        return "Order #" + id + " confirmed — routed via API Gateway to order-service (port 9082)";
    }
}
