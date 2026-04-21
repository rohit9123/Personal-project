# Spring Cloud Bus — Concept Notes + Runnable Demo

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create concept notes and a runnable multi-module Spring Boot demo that shows (1) broadcasting a config refresh to ALL services via one `POST /actuator/busrefresh`, and (2) publishing/consuming custom domain events across different services — all over Spring Cloud Bus + RabbitMQ.

**Architecture:**
- `config-server` — Spring Cloud Config Server + Bus AMQP (port 8888)
- `order-service` — Config client + Bus; exposes `POST /orders` which publishes a custom `OrderCreatedEvent` to the bus (port 9081)
- `inventory-service` — Config client + Bus; listens for `OrderCreatedEvent` via `@EventListener` and logs the reservation (port 9082)
- All three connect to the same RabbitMQ exchange `springCloudBus`. A single `POST /actuator/busrefresh` on any service refreshes config on all three simultaneously.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring Cloud 2023.0.1, `spring-cloud-starter-bus-amqp` (pulls in RabbitMQ binder), RabbitMQ via Docker

---

## File Map

| File | Action |
|------|--------|
| `microservices/spring-cloud-bus/notes.md` | CREATE — concept notes |
| `microservices/spring-cloud-bus/pom.xml` | CREATE — parent POM |
| `microservices/spring-cloud-bus/config-repo/order-service.yml` | CREATE |
| `microservices/spring-cloud-bus/config-repo/inventory-service.yml` | CREATE |
| `microservices/spring-cloud-bus/config-server/pom.xml` | CREATE |
| `microservices/spring-cloud-bus/config-server/src/main/java/com/example/configserver/ConfigServerApp.java` | CREATE |
| `microservices/spring-cloud-bus/config-server/src/main/resources/application.yml` | CREATE |
| `microservices/spring-cloud-bus/config-server/src/test/java/com/example/configserver/ConfigServerAppTest.java` | CREATE |
| `microservices/spring-cloud-bus/order-service/pom.xml` | CREATE |
| `microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/OrderServiceApp.java` | CREATE |
| `microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/events/OrderCreatedEvent.java` | CREATE |
| `microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/OrderController.java` | CREATE |
| `microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/OrderConfigProperties.java` | CREATE |
| `microservices/spring-cloud-bus/order-service/src/main/resources/application.yml` | CREATE |
| `microservices/spring-cloud-bus/order-service/src/test/resources/application-test.yml` | CREATE |
| `microservices/spring-cloud-bus/order-service/src/test/java/com/example/orderservice/OrderServiceAppTest.java` | CREATE |
| `microservices/spring-cloud-bus/inventory-service/pom.xml` | CREATE |
| `microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/InventoryServiceApp.java` | CREATE |
| `microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/events/OrderCreatedEvent.java` | CREATE |
| `microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/InventoryEventListener.java` | CREATE |
| `microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/InventoryConfigProperties.java` | CREATE |
| `microservices/spring-cloud-bus/inventory-service/src/main/resources/application.yml` | CREATE |
| `microservices/spring-cloud-bus/inventory-service/src/test/resources/application-test.yml` | CREATE |
| `microservices/spring-cloud-bus/inventory-service/src/test/java/com/example/inventoryservice/InventoryServiceAppTest.java` | CREATE |

---

## Task 1: Concept Notes

**Files:**
- Create: `microservices/spring-cloud-bus/notes.md`

- [ ] **Step 1: Create the notes file**

```markdown
# Spring Cloud Bus

## What

Spring Cloud Bus links nodes of a distributed system with a lightweight message broker (RabbitMQ or Kafka).  
It propagates state changes — like a config refresh or a custom domain event — to ALL connected service instances at once.

Without Bus, broadcasting a config change to 10 services means 10 separate `POST /actuator/refresh` calls.  
With Bus, one `POST /actuator/busrefresh` fans out to all 10 automatically.

## Why

| Problem | Without Bus | With Bus |
|---------|-------------|----------|
| Config refresh | Hit each service manually | Hit one service → all refresh |
| Cross-service events | REST call or Kafka wiring | Publish once → all subscribers receive |
| N services refresh | O(N) actuator calls | O(1) |

Bus is especially useful when:
- You have auto-scaled instances (unknown count) that all need config refreshed
- You want lightweight domain events between services without setting up full Kafka topics

## How

1. Add `spring-cloud-starter-bus-amqp` (or `-kafka`) to every service that needs Bus.
2. All services auto-connect to the same RabbitMQ exchange `springCloudBus` at startup.
3. Any message published to that exchange is delivered to every subscriber.

### Config Refresh Flow

```
           ┌─────────────────────────────────────────────┐
           │  Developer edits config-repo/order-service.yml │
           └────────────────────┬────────────────────────┘
                                │
                POST /actuator/busrefresh (on ANY service)
                                │
                                ▼
                    ┌───────────────────┐
                    │  RabbitMQ         │
                    │  springCloudBus   │
                    └──────┬────────────┘
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
    config-server    order-service   inventory-service
    (re-reads git)   (@RefreshScope  (@RefreshScope
                      re-created)     re-created)
```

### Custom Domain Event Flow

```
  order-service                          inventory-service
  ─────────────                          ─────────────────
  POST /orders
       │
  publisher.publishEvent(OrderCreatedEvent)
       │
  Bus serializes → RabbitMQ ──────────► @EventListener(OrderCreatedEvent)
                                                │
                                          reserve stock
```

## Code Example

### 1. Enable Bus on all three services — just add the dependency (no annotation needed):

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bus-amqp</artifactId>
</dependency>
```

### 2. Custom event — must extend `RemoteApplicationEvent`:

```java
// Same class, same package in BOTH services (needed for deserialization)
public class OrderCreatedEvent extends RemoteApplicationEvent {
    private String orderId;
    private String product;
    private int quantity;

    public OrderCreatedEvent() {}  // required for Jackson

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

### 3. Register the event package with `@RemoteApplicationEventScan` on App class:

```java
@SpringBootApplication
@RemoteApplicationEventScan(basePackages = "com.example.orderservice.events")
public class OrderServiceApp { ... }
```

### 4. Publish the event from order-service:

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

### 5. Receive in inventory-service:

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

### 6. Broadcast config refresh to all services at once:

```bash
# Edit config-repo/order-service.yml, then:
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
Spring Cloud Bus deserializes incoming JSON messages using the fully-qualified class name embedded in the message type header. If the class name matches a class registered via `@RemoteApplicationEventScan`, it deserializes it. If the package differs, deserialization fails (unknown type).

**Q: Can you use Kafka instead of RabbitMQ?**  
Yes — replace `spring-cloud-starter-bus-amqp` with `spring-cloud-starter-bus-kafka`. No code changes needed; Bus is binder-agnostic.

**Q: What happens if RabbitMQ is down?**  
Services fail to start if Bus is enabled and the broker is unreachable. Use `spring.cloud.bus.enabled: false` in tests/local profiles to avoid this.
```

- [ ] **Step 2: Commit**

```bash
git add microservices/spring-cloud-bus/notes.md
git commit -m "docs: add Spring Cloud Bus concept notes (What/Why/How/Code/Interview Angles)"
```

---

## Task 2: Parent POM

**Files:**
- Create: `microservices/spring-cloud-bus/pom.xml`

- [ ] **Step 1: Create parent POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>spring-cloud-bus-demo</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>Spring Cloud Bus Demo</name>

    <properties>
        <java.version>17</java.version>
        <spring-cloud.version>2023.0.1</spring-cloud.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <modules>
        <module>config-server</module>
        <module>order-service</module>
        <module>inventory-service</module>
    </modules>

</project>
```

- [ ] **Step 2: Verify parent POM is valid**

```bash
cd microservices/spring-cloud-bus
# Check modules exist (will fail for now — that's expected, just checking syntax)
cat pom.xml | grep -E '<module>|<artifactId>'
```

Expected output includes `config-server`, `order-service`, `inventory-service`.

---

## Task 3: Config Repo Files

**Files:**
- Create: `microservices/spring-cloud-bus/config-repo/order-service.yml`
- Create: `microservices/spring-cloud-bus/config-repo/inventory-service.yml`

- [ ] **Step 1: Create order-service config**

```yaml
# microservices/spring-cloud-bus/config-repo/order-service.yml
order:
  greeting: "Hello from Order Service — loaded from Config Server"
  max-items: 50
```

- [ ] **Step 2: Create inventory-service config**

```yaml
# microservices/spring-cloud-bus/config-repo/inventory-service.yml
inventory:
  greeting: "Hello from Inventory Service — loaded from Config Server"
  stock-threshold: 10
```

- [ ] **Step 3: Commit**

```bash
git add microservices/spring-cloud-bus/config-repo/
git commit -m "chore: add config-repo files for bus demo"
```

---

## Task 4: Config Server

**Files:**
- Create: `microservices/spring-cloud-bus/config-server/pom.xml`
- Create: `microservices/spring-cloud-bus/config-server/src/main/java/com/example/configserver/ConfigServerApp.java`
- Create: `microservices/spring-cloud-bus/config-server/src/main/resources/application.yml`
- Create: `microservices/spring-cloud-bus/config-server/src/test/java/com/example/configserver/ConfigServerAppTest.java`

- [ ] **Step 1: Write the failing context test**

```java
// microservices/spring-cloud-bus/config-server/src/test/java/com/example/configserver/ConfigServerAppTest.java
package com.example.configserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.cloud.bus.enabled=false",
    "spring.cloud.config.server.native.search-locations=classpath:/config-repo"
})
class ConfigServerAppTest {
    @Test
    void contextLoads() {}
}
```

- [ ] **Step 2: Run test to confirm it fails (class missing)**

```bash
cd microservices/spring-cloud-bus/config-server
mvn test -pl .
```

Expected: BUILD FAILURE — `ConfigServerApp` does not exist yet.

- [ ] **Step 3: Create config-server pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>spring-cloud-bus-demo</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>config-server</artifactId>
    <name>Config Server (Bus)</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-config-server</artifactId>
        </dependency>
        <!-- Bus AMQP: brings in spring-cloud-bus + spring-cloud-stream-binder-rabbit -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-bus-amqp</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

- [ ] **Step 4: Create ConfigServerApp.java**

```java
// microservices/spring-cloud-bus/config-server/src/main/java/com/example/configserver/ConfigServerApp.java
package com.example.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

// @EnableConfigServer turns this into a Spring Cloud Config Server.
// spring-cloud-starter-bus-amqp on the classpath auto-configures Bus —
// no extra annotation needed. At startup this service joins the
// 'springCloudBus' RabbitMQ exchange and can receive/send bus events.
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApp {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApp.class, args);
    }
}
```

- [ ] **Step 5: Create application.yml**

```yaml
# microservices/spring-cloud-bus/config-server/src/main/resources/application.yml
spring:
  application:
    name: config-server
  profiles:
    active: native
  cloud:
    config:
      server:
        native:
          # Run 'mvn spring-boot:run' from inside config-server/ — ../config-repo resolves correctly
          search-locations: file:../config-repo
    bus:
      enabled: true          # explicit — already true by default when starter is present

  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

server:
  port: 8888

management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 6: Run test to confirm it passes**

```bash
cd microservices/spring-cloud-bus
mvn test -pl config-server
```

Expected: BUILD SUCCESS — context loads with Bus disabled in test.

- [ ] **Step 7: Commit**

```bash
git add microservices/spring-cloud-bus/config-server/
git commit -m "feat: add config-server with Spring Cloud Bus AMQP"
```

---

## Task 5: Order Service

**Files:**
- Create: `microservices/spring-cloud-bus/order-service/pom.xml`
- Create: `microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/events/OrderCreatedEvent.java`
- Create: `microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/OrderServiceApp.java`
- Create: `microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/OrderController.java`
- Create: `microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/OrderConfigProperties.java`
- Create: `microservices/spring-cloud-bus/order-service/src/main/resources/application.yml`
- Create: `microservices/spring-cloud-bus/order-service/src/test/resources/application-test.yml`
- Create: `microservices/spring-cloud-bus/order-service/src/test/java/com/example/orderservice/OrderServiceAppTest.java`

- [ ] **Step 1: Write the failing context test**

```java
// microservices/spring-cloud-bus/order-service/src/test/java/com/example/orderservice/OrderServiceAppTest.java
package com.example.orderservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// 'test' profile loads application-test.yml which disables Bus + Config Server import
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class OrderServiceAppTest {
    @Test
    void contextLoads() {}
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd microservices/spring-cloud-bus
mvn test -pl order-service
```

Expected: BUILD FAILURE — `OrderServiceApp` does not exist yet.

- [ ] **Step 3: Create order-service pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>spring-cloud-bus-demo</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>order-service</artifactId>
    <name>Order Service (Bus Publisher)</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <!-- Fetches config from Config Server at startup -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <!-- Bus AMQP: connects to RabbitMQ, enables /actuator/busrefresh,
             and allows publishing RemoteApplicationEvents -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-bus-amqp</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

- [ ] **Step 4: Create OrderCreatedEvent.java**

```java
// microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/events/OrderCreatedEvent.java
package com.example.orderservice.events;

import org.springframework.cloud.bus.event.RemoteApplicationEvent;

/**
 * Custom bus event — sent from order-service, received by any service
 * that has @RemoteApplicationEventScan pointing to this package.
 *
 * Rules for RemoteApplicationEvent subclasses:
 *   1. Must have a no-arg constructor (Jackson deserialization).
 *   2. The SAME fully-qualified class name must exist in the receiving service.
 *      (In a real codebase this would be a shared library.)
 *   3. Both services must register the package via @RemoteApplicationEventScan.
 *
 * destinationService patterns:
 *   "**"                    → broadcast to all bus-connected services
 *   "inventory-service:**"  → all instances of inventory-service only
 *   "inventory-service:0"   → specific instance
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
        super(source, originService, destinationService);
        this.orderId  = orderId;
        this.product  = product;
        this.quantity = quantity;
    }

    public String getOrderId()  { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getProduct()  { return product; }
    public void setProduct(String product) { this.product = product; }

    public int getQuantity()    { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
```

- [ ] **Step 5: Create OrderServiceApp.java**

```java
// microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/OrderServiceApp.java
package com.example.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.bus.jackson.RemoteApplicationEventScan;

// @RemoteApplicationEventScan tells Spring Cloud Bus to register
// OrderCreatedEvent for Jackson serialization/deserialization.
// Without this, custom events are not recognised on deserialization.
@SpringBootApplication
@ConfigurationPropertiesScan
@RemoteApplicationEventScan(basePackages = "com.example.orderservice.events")
public class OrderServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApp.class, args);
    }
}
```

- [ ] **Step 6: Create OrderConfigProperties.java**

```java
// microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/OrderConfigProperties.java
package com.example.orderservice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * Binds to the 'order.*' properties served by Config Server.
 * @RefreshScope means this bean is re-created when /actuator/busrefresh fires —
 * so live config updates work without restarting.
 */
@RefreshScope
@ConfigurationProperties(prefix = "order")
public class OrderConfigProperties {

    private String greeting = "default greeting";
    private int    maxItems = 10;

    public String getGreeting() { return greeting; }
    public void setGreeting(String greeting) { this.greeting = greeting; }

    public int getMaxItems()    { return maxItems; }
    public void setMaxItems(int maxItems) { this.maxItems = maxItems; }
}
```

- [ ] **Step 7: Create OrderController.java**

```java
// microservices/spring-cloud-bus/order-service/src/main/java/com/example/orderservice/OrderController.java
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
     * GET /orders/config — shows current config (changes on busrefresh)
     *
     * Demo:
     *   1. GET /orders/config              — note current greeting
     *   2. Edit config-repo/order-service.yml (change greeting)
     *   3. POST /actuator/busrefresh       — broadcasts refresh to all services
     *   4. GET /orders/config              — greeting has updated, no restart
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
            "greeting", config.getGreeting(),
            "maxItems", config.getMaxItems(),
            "busId",    busProperties.getId()
        );
    }

    /**
     * POST /orders
     * Body: { "orderId": "101", "product": "laptop", "quantity": 2 }
     * Param: destination (default "**" = broadcast to all, or "inventory-service:**")
     *
     * Publishes OrderCreatedEvent to the bus.
     * Spring Cloud Bus serializes it as JSON → RabbitMQ → all subscribed services.
     * inventory-service receives it via @EventListener(OrderCreatedEvent.class).
     */
    @PostMapping
    public Map<String, String> placeOrder(
            @RequestBody  OrderRequest request,
            @RequestParam(defaultValue = "**") String destination) {

        var event = new OrderCreatedEvent(
            this,
            busProperties.getId(),   // this service's bus ID: "order-service:9081:uuid"
            destination,
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

    // Simple record for request body
    public record OrderRequest(String orderId, String product, int quantity) {}
}
```

- [ ] **Step 8: Create application.yml**

```yaml
# microservices/spring-cloud-bus/order-service/src/main/resources/application.yml
spring:
  application:
    name: order-service         # Config Server looks up 'order-service.yml' using this name
  config:
    import: "configserver:http://localhost:8888"
  cloud:
    bus:
      enabled: true
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

server:
  port: 9081

management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 9: Create test profile override**

```yaml
# microservices/spring-cloud-bus/order-service/src/test/resources/application-test.yml
spring:
  cloud:
    config:
      enabled: false    # do not call Config Server in tests
    bus:
      enabled: false    # do not connect to RabbitMQ in tests
  config:
    import: optional:configserver:   # suppress the mandatory configserver import

# Supply the properties that the app expects (normally from Config Server)
order:
  greeting: "test greeting"
  max-items: 5
```

- [ ] **Step 10: Run test to confirm it passes**

```bash
cd microservices/spring-cloud-bus
mvn test -pl order-service
```

Expected: BUILD SUCCESS

- [ ] **Step 11: Commit**

```bash
git add microservices/spring-cloud-bus/order-service/
git commit -m "feat: add order-service — publishes OrderCreatedEvent via Spring Cloud Bus"
```

---

## Task 6: Inventory Service

**Files:**
- Create: `microservices/spring-cloud-bus/inventory-service/pom.xml`
- Create: `microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/events/OrderCreatedEvent.java`
- Create: `microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/InventoryServiceApp.java`
- Create: `microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/InventoryEventListener.java`
- Create: `microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/InventoryConfigProperties.java`
- Create: `microservices/spring-cloud-bus/inventory-service/src/main/resources/application.yml`
- Create: `microservices/spring-cloud-bus/inventory-service/src/test/resources/application-test.yml`
- Create: `microservices/spring-cloud-bus/inventory-service/src/test/java/com/example/inventoryservice/InventoryServiceAppTest.java`

- [ ] **Step 1: Write the failing context test**

```java
// microservices/spring-cloud-bus/inventory-service/src/test/java/com/example/inventoryservice/InventoryServiceAppTest.java
package com.example.inventoryservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class InventoryServiceAppTest {
    @Test
    void contextLoads() {}
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd microservices/spring-cloud-bus
mvn test -pl inventory-service
```

Expected: BUILD FAILURE — `InventoryServiceApp` does not exist yet.

- [ ] **Step 3: Create inventory-service pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>spring-cloud-bus-demo</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>inventory-service</artifactId>
    <name>Inventory Service (Bus Listener)</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-bus-amqp</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

- [ ] **Step 4: Create OrderCreatedEvent.java (same class, same relative package)**

```java
// microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/events/OrderCreatedEvent.java
package com.example.inventoryservice.events;

import org.springframework.cloud.bus.event.RemoteApplicationEvent;

/**
 * Mirror of order-service's OrderCreatedEvent.
 *
 * CRITICAL: In a real project this would be in a shared library jar.
 * For a demo, we duplicate the class. The package name CAN differ between
 * services as long as BOTH services register their own package with
 * @RemoteApplicationEventScan — Spring Cloud Bus uses the simple class name
 * for the type header and scans registered packages to find the matching class.
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

    public String getOrderId()  { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getProduct()  { return product; }
    public void setProduct(String product) { this.product = product; }

    public int getQuantity()    { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
```

- [ ] **Step 5: Create InventoryServiceApp.java**

```java
// microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/InventoryServiceApp.java
package com.example.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.bus.jackson.RemoteApplicationEventScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@RemoteApplicationEventScan(basePackages = "com.example.inventoryservice.events")
public class InventoryServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApp.class, args);
    }
}
```

- [ ] **Step 6: Create InventoryEventListener.java**

```java
// microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/InventoryEventListener.java
package com.example.inventoryservice;

import com.example.inventoryservice.events.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for OrderCreatedEvent messages arriving over Spring Cloud Bus.
 *
 * Spring Cloud Bus deserializes the incoming RabbitMQ JSON message into
 * OrderCreatedEvent (because @RemoteApplicationEventScan registered the class),
 * then publishes it to the Spring ApplicationContext as a regular application event.
 * @EventListener picks it up here — no special Bus annotation needed.
 *
 * The event is NOT received by the service that published it (Spring Cloud Bus
 * skips local delivery of self-originated events).
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

        // In a real system: deduct stock, schedule fulfillment, etc.
        log.info("Reserving {} unit(s) of '{}' for order {}",
            event.getQuantity(), event.getProduct(), event.getOrderId());
    }
}
```

- [ ] **Step 7: Create InventoryConfigProperties.java**

```java
// microservices/spring-cloud-bus/inventory-service/src/main/java/com/example/inventoryservice/InventoryConfigProperties.java
package com.example.inventoryservice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

@RefreshScope
@ConfigurationProperties(prefix = "inventory")
public class InventoryConfigProperties {

    private String greeting       = "default greeting";
    private int    stockThreshold = 5;

    public String getGreeting()       { return greeting; }
    public void setGreeting(String g) { this.greeting = g; }

    public int getStockThreshold()       { return stockThreshold; }
    public void setStockThreshold(int t) { this.stockThreshold = t; }
}
```

- [ ] **Step 8: Create application.yml**

```yaml
# microservices/spring-cloud-bus/inventory-service/src/main/resources/application.yml
spring:
  application:
    name: inventory-service
  config:
    import: "configserver:http://localhost:8888"
  cloud:
    bus:
      enabled: true
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

server:
  port: 9082

management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 9: Create test profile override**

```yaml
# microservices/spring-cloud-bus/inventory-service/src/test/resources/application-test.yml
spring:
  cloud:
    config:
      enabled: false
    bus:
      enabled: false
  config:
    import: optional:configserver:

inventory:
  greeting: "test greeting"
  stock-threshold: 5
```

- [ ] **Step 10: Run all tests to confirm they pass**

```bash
cd microservices/spring-cloud-bus
mvn clean test
```

Expected: BUILD SUCCESS — all 3 modules pass context load tests.

- [ ] **Step 11: Commit**

```bash
git add microservices/spring-cloud-bus/inventory-service/ microservices/spring-cloud-bus/pom.xml
git commit -m "feat: add inventory-service — listens for OrderCreatedEvent via Spring Cloud Bus"
```

---

## Task 7: End-to-End Demo Walk-Through

This task verifies the full demo works. Requires Docker + ports 5672, 8888, 9081, 9082 free.

- [ ] **Step 1: Start RabbitMQ**

```bash
docker run -d --name rabbitmq \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:3-management
# Wait ~10s for RabbitMQ to be ready
# Admin UI: http://localhost:15672  (guest / guest)
```

- [ ] **Step 2: Start Config Server**

```bash
cd microservices/spring-cloud-bus/config-server
mvn spring-boot:run
# Wait for "Started ConfigServerApp" in logs
# Verify: curl http://localhost:8888/order-service/default | jq .
```

Expected: JSON containing `order.greeting` and `order.max-items`.

- [ ] **Step 3: Start Order Service and Inventory Service (two terminals)**

```bash
# Terminal 1
cd microservices/spring-cloud-bus/order-service
mvn spring-boot:run

# Terminal 2
cd microservices/spring-cloud-bus/inventory-service
mvn spring-boot:run
```

Both should log `Connected to RabbitMQ` and `Fetched config from server`.

- [ ] **Step 4: Demo — Config Broadcast Refresh**

```bash
# See current config on order-service
curl http://localhost:9081/orders/config

# Edit config-repo/order-service.yml — change 'greeting' value
# Then broadcast refresh to ALL services at once:
curl -X POST http://localhost:9081/actuator/busrefresh

# Both services pick up new config:
curl http://localhost:9081/orders/config    # updated
curl http://localhost:9082/actuator/env | jq '."inventory.greeting"'  # also updated
```

- [ ] **Step 5: Demo — Custom Cross-Service Event**

```bash
# Broadcast to all services
curl -s -X POST http://localhost:9081/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"101","product":"laptop","quantity":2}' | jq .

# Watch inventory-service logs — you should see:
# [BUS EVENT RECEIVED] OrderCreatedEvent — id=101, product=laptop, qty=2
# Reserving 2 unit(s) of 'laptop' for order 101

# Targeted event — only inventory-service receives it
curl -s -X POST "http://localhost:9081/orders?destination=inventory-service:**" \
  -H "Content-Type: application/json" \
  -d '{"orderId":"102","product":"mouse","quantity":5}' | jq .
```

- [ ] **Step 6: Final commit**

```bash
git add microservices/spring-cloud-bus/
git commit -m "feat: complete Spring Cloud Bus demo — config broadcast + custom domain events"
```

---

## Self-Review

**Spec coverage:**
- [x] Concept notes in What/Why/How/Code/Interview Angles format
- [x] Config Server with Bus AMQP support
- [x] Config broadcast refresh (`/actuator/busrefresh`) across all services
- [x] Custom `RemoteApplicationEvent` published from order-service
- [x] `@EventListener` in inventory-service receives cross-service event
- [x] Targeted events via `destinationService` parameter
- [x] Context load tests for all 3 services
- [x] Test profiles that disable Bus + Config Server for offline testing
- [x] End-to-end demo walk-through with exact curl commands

**Placeholder scan:** No TBDs. All code blocks are complete.

**Type consistency:**
- `OrderCreatedEvent` constructor signature is identical in Tasks 5 and 6
- `busProperties.getId()` used in Task 5 — `BusProperties` is auto-configured by `spring-cloud-starter-bus-amqp`, no manual bean needed
- `OrderConfigProperties` prefix `"order"` matches `config-repo/order-service.yml` keys
- `InventoryConfigProperties` prefix `"inventory"` matches `config-repo/inventory-service.yml` keys
