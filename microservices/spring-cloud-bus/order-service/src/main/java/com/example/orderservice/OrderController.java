package com.example.orderservice;

import com.example.orderservice.events.OrderCreatedEvent;
import org.springframework.cloud.bus.BusProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final ApplicationEventPublisher publisher;
    private final BusProperties             busProperties;
    private final OrderConfigProperties     config;

    public OrderController(ApplicationEventPublisher publisher,
                           BusProperties busProperties,
                           OrderConfigProperties config) {
        this.publisher     = publisher;
        this.busProperties = busProperties;
        this.config        = config;
    }

    /**
     * GET /orders/config — shows current config (live-updated on /actuator/busrefresh).
     *
     * Demo:
     *   1. GET /orders/config              — note current greeting
     *   2. Edit config-repo/order-service.yml (change greeting)
     *   3. POST /actuator/busrefresh       — broadcasts refresh to all services
     *   4. GET /orders/config              — greeting is updated, no restart needed
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
            "greeting", config.getGreeting(),
            "maxItems", config.getMaxItems(),
            "busId",    busProperties.getId()   // shows this service's bus ID: "order-service:9081:uuid"
        );
    }

    /**
     * POST /orders
     * Body:  { "orderId": "101", "product": "laptop", "quantity": 2 }
     * Param: destination (default "**" = broadcast; use "inventory-service:**" to target)
     *
     * Publishes OrderCreatedEvent to the bus.
     * Spring Cloud Bus serializes it as JSON → RabbitMQ exchange → all subscribed services.
     * inventory-service receives it via @EventListener(OrderCreatedEvent.class).
     *
     * Note: the publisher service does NOT receive its own event back
     * (Spring Cloud Bus suppresses self-delivery).
     */
    @PostMapping
    public Map<String, String> placeOrder(
            @RequestBody  OrderRequest request,
            @RequestParam(defaultValue = "**") String destination) {

        var event = new OrderCreatedEvent(
            this,
            busProperties.getId(),   // origin: "order-service:9081:uuid"
            destination,             // "**" or "inventory-service:**"
            request.orderId(),
            request.product(),
            request.quantity()
        );
        publisher.publishEvent(event);

        return Map.of(
            "status",      "published",
            "orderId",     request.orderId(),
            "destination", destination
        );
    }

    // Record for the request body — replaces a separate DTO class
    public record OrderRequest(String orderId, String product, int quantity) {}
}
