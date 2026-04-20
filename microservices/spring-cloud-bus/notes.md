# Spring Cloud Bus

## What

Spring Cloud Bus links nodes of a distributed system with a lightweight message broker (RabbitMQ or Kafka). It propagates state changes — like a config refresh or a custom domain event — to ALL connected service instances at once.

Without Bus, broadcasting a config change to 10 services means 10 separate `POST /actuator/refresh` calls. With Bus, one `POST /actuator/busrefresh` fans out to all 10 automatically.

## Why

| Problem | Without Bus | With Bus |
|---------|-------------|----------|
| Config refresh | Hit each service manually | Hit one service → all refresh |
| Cross-service events | REST call or Kafka wiring | Publish once → all subscribers receive |
| N services refresh | O(N) actuator calls | O(1) |

Use Bus when:
- You have auto-scaled instances (unknown count) that all need config refreshed
- You want lightweight domain events between services without full Kafka topic setup

## How

1. Add `spring-cloud-starter-bus-amqp` (or `-kafka`) to every service.
2. All services auto-connect to the same RabbitMQ exchange `springCloudBus` at startup.
3. Any message published to that exchange is delivered to every subscriber.

**Config Refresh Flow:**
```
POST /actuator/busrefresh (on ANY service)
        │
        ▼
  RabbitMQ: springCloudBus exchange
        │
  ┌─────┴──────┬──────────────┐
  ▼            ▼              ▼
config-server  order-service  inventory-service
(re-reads)    (@RefreshScope  (@RefreshScope
               re-created)    re-created)
```

**Custom Domain Event Flow:**
```
order-service                        inventory-service
─────────────                        ─────────────────
POST /orders
     │
publisher.publishEvent(OrderCreatedEvent)
     │
Bus serializes → RabbitMQ ─────────► @EventListener(OrderCreatedEvent)
                                              │
                                        reserve stock
```

## Code Example

**1. Add Bus dependency to every service (no annotation needed for config refresh):**
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bus-amqp</artifactId>
</dependency>
```

**2. Custom event — extend `RemoteApplicationEvent`:**
```java
// Same simple class name in BOTH services (needed for deserialization)
public class OrderCreatedEvent extends RemoteApplicationEvent {
    private String orderId;
    private String product;
    private int quantity;

    public OrderCreatedEvent() {}   // required for Jackson

    public OrderCreatedEvent(Object source, String originService,
                             String destinationService,
                             String orderId, String product, int quantity) {
        super(source, originService, destinationService);
        this.orderId = orderId;
        this.product = product;
        this.quantity = quantity;
    }
    // getters/setters ...
}
```

**3. Register the event package with `@RemoteApplicationEventScan` on App class:**
```java
@SpringBootApplication
@RemoteApplicationEventScan(basePackages = "com.example.orderservice.events")
public class OrderServiceApp { ... }
```

**4. Publish the event (order-service):**
```java
@Autowired ApplicationEventPublisher publisher;
@Autowired BusProperties busProperties;

// Broadcast to ALL services:
publisher.publishEvent(new OrderCreatedEvent(
    this, busProperties.getId(), "**",
    orderId, product, quantity));

// Target only inventory-service instances:
publisher.publishEvent(new OrderCreatedEvent(
    this, busProperties.getId(), "inventory-service:**",
    orderId, product, quantity));
```

**5. Receive in inventory-service:**
```java
@Component
public class InventoryEventListener {
    @EventListener
    public void handle(OrderCreatedEvent event) {
        log.info("Reserve {} x {} — origin: {}",
            event.getQuantity(), event.getProduct(), event.getOriginService());
    }
}
```

**6. Broadcast config refresh to all services at once:**
```bash
# Edit config-repo/order-service.yml (change greeting), then:
curl -X POST http://localhost:9081/actuator/busrefresh
# Both order-service and inventory-service pick up new config — no restart
```

## Interview Angles

**Q: What is the difference between `/actuator/refresh` and `/actuator/busrefresh`?**
`/actuator/refresh` refreshes only the single instance it is called on. `/actuator/busrefresh` publishes a `RefreshRemoteApplicationEvent` to the bus, which all connected service instances receive and process — refreshing themselves automatically.

**Q: How does Spring Cloud Bus know which services to notify?**
Every service subscribes to the same RabbitMQ exchange (`springCloudBus`) at startup. The destination field in the event is a glob pattern matched against each service's ID (`appName:port:instanceId`). `**` matches everything; `inventory-service:**` matches all instances of inventory-service only.

**Q: What's the difference between Spring Cloud Bus events and regular Kafka topics?**
Bus events are transient broadcasts — fire-and-forget, no replay. Kafka topics persist messages and support consumer groups/replay. Use Bus for operational signals (config refresh, cache invalidation) and Kafka for durable domain events that need ordering guarantees or replay.

**Q: Why must `OrderCreatedEvent` be in the same package in both services?**
Spring Cloud Bus deserializes incoming JSON messages using the simple class name embedded in the message type header. If the class is registered via `@RemoteApplicationEventScan`, it deserializes it. If the class can't be found by scanning, deserialization fails.

**Q: Can you use Kafka instead of RabbitMQ?**
Yes — replace `spring-cloud-starter-bus-amqp` with `spring-cloud-starter-bus-kafka`. No code changes needed; Bus is binder-agnostic.

**Q: What happens if RabbitMQ is down?**
Services fail to start if Bus is enabled and the broker is unreachable. Use `spring.cloud.bus.enabled: false` in test profiles to avoid this.
