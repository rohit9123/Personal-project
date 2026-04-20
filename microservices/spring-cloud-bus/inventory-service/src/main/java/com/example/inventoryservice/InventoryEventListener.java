package com.example.inventoryservice;

import com.example.inventoryservice.events.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for OrderCreatedEvent messages arriving over Spring Cloud Bus.
 *
 * Flow:
 *   1. order-service publishes OrderCreatedEvent via ApplicationEventPublisher.
 *   2. Spring Cloud Bus serializes it as JSON → RabbitMQ exchange 'springCloudBus'.
 *   3. inventory-service is subscribed to that exchange.
 *   4. Bus deserializes the JSON into OrderCreatedEvent (using the simple class name
 *      to find the class registered via @RemoteApplicationEventScan).
 *   5. Bus re-publishes it to the local Spring ApplicationContext.
 *   6. @EventListener picks it up here — no Bus-specific annotation needed.
 *
 * Note: the originating service (order-service) does NOT receive its own event back.
 * Spring Cloud Bus suppresses self-delivery by comparing the incoming originService
 * against the local service ID.
 */
@Component
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    @EventListener
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("[BUS EVENT RECEIVED] OrderCreatedEvent — id={}, product={}, qty={}, from={}",
            event.getOrderId(),
            event.getProduct(),
            event.getQuantity(),
            event.getOriginService());

        // In a real system: deduct stock, schedule fulfilment, emit a domain event, etc.
        log.info("Reserving {} unit(s) of '{}' for order {}",
            event.getQuantity(), event.getProduct(), event.getOrderId());
    }
}
