package com.example.orderservice.events;

import org.springframework.cloud.bus.event.PathDestinationFactory;
import org.springframework.cloud.bus.event.RemoteApplicationEvent;

/**
 * Custom bus event — sent from order-service, received by any service
 * that has @RemoteApplicationEventScan pointing to a package containing
 * a class with the same simple name.
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

    // Required for Jackson deserialization on the receiving end
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

    public String getOrderId()              { return orderId; }
    public void   setOrderId(String id)     { this.orderId = id; }

    public String getProduct()              { return product; }
    public void   setProduct(String p)      { this.product = p; }

    public int    getQuantity()             { return quantity; }
    public void   setQuantity(int q)        { this.quantity = q; }
}
