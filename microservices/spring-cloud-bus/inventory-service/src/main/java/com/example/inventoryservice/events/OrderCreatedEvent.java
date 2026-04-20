package com.example.inventoryservice.events;

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
        super(source, originService, destinationService);
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
