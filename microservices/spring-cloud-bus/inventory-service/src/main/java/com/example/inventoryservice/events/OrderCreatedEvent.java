package com.example.inventoryservice.events;

import org.springframework.cloud.bus.event.PathDestinationFactory;
import org.springframework.cloud.bus.event.RemoteApplicationEvent;

/**
 * Mirror of order-service's OrderCreatedEvent.
 *
 * In a production codebase this would be in a shared library jar so both
 * services depend on the same class. For a demo, we duplicate it here.
 *
 * Why this works: Spring Cloud Bus uses the SIMPLE class name (OrderCreatedEvent)
 * — not the fully-qualified name — in the message type header. When the message
 * arrives, Bus scans packages registered via @RemoteApplicationEventScan for a
 * class with that simple name. The package can differ between services; only the
 * simple name must match.
 *
 * Rules for RemoteApplicationEvent subclasses:
 *   1. Must have a no-arg constructor (Jackson deserialization).
 *   2. The receiving service must have a class with the same SIMPLE name
 *      (e.g. OrderCreatedEvent) registered via @RemoteApplicationEventScan.
 *      The package can differ between services — Bus uses the simple class
 *      name (not the FQN) in the message type header.
 *   3. Both services must declare @RemoteApplicationEventScan on their App class.
 *
 * destinationService patterns:
 *   "**"                    → broadcast to all bus-connected services
 *   "inventory-service:**"  → all instances of inventory-service only
 *   "inventory-service:0"   → specific instance (index 0)
 *
 * Note: The {@code super(source, originService, destinationService)} String
 * overload of {@link RemoteApplicationEvent} is deprecated in Spring Cloud Bus
 * 4.x. This constructor uses the non-deprecated
 * {@link PathDestinationFactory#getDestination(String)} overload instead.
 */
public class OrderCreatedEvent extends RemoteApplicationEvent {

    private String orderId;
    private String product;
    private int    quantity;

    public OrderCreatedEvent() {}

    public OrderCreatedEvent(Object source,
                             String originService,
                             String destinationService,
                             String orderId,
                             String product,
                             int    quantity) {
        super(source, originService,
              new PathDestinationFactory().getDestination(destinationService));
        this.orderId  = orderId;
        this.product  = product;
        this.quantity = quantity;
    }

    public String getOrderId()             { return orderId; }
    public void   setOrderId(String id)    { this.orderId = id; }

    public String getProduct()             { return product; }
    public void   setProduct(String p)     { this.product = p; }

    public int    getQuantity()            { return quantity; }
    public void   setQuantity(int q)       { this.quantity = q; }
}
