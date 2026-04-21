# Distributed Tracing Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a two-service Spring Boot demo (order-service → inventory-service) with Micrometer Tracing + OTel bridge, shipping traces via OTLP to Grafana Tempo, viewable in Grafana.

**Architecture:** `order-service` (port 9081) receives `GET /orders/{id}`, calls `inventory-service` (port 9082) via `RestTemplate`. Both services export spans to an OTel Collector (port 4318) which forwards to Grafana Tempo, queried by Grafana. No Eureka — the focus is tracing, not discovery.

**Tech Stack:** Spring Boot 3.2.5, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, Docker Compose (OTel Collector 0.96, Grafana Tempo 2.4, Grafana 10.2)

---

## File Map

| Path | Action | Purpose |
|------|--------|---------|
| `microservices/distributed-tracing/pom.xml` | Create | Parent POM, two modules |
| `microservices/distributed-tracing/inventory-service/pom.xml` | Create | Service deps (web, actuator, tracing, otlp) |
| `microservices/distributed-tracing/inventory-service/src/main/java/com/example/inventory/InventoryServiceApp.java` | Create | `@SpringBootApplication` entry point |
| `microservices/distributed-tracing/inventory-service/src/main/java/com/example/inventory/InventoryController.java` | Create | `GET /inventory/{itemId}` handler |
| `microservices/distributed-tracing/inventory-service/src/main/resources/application.yml` | Create | Port 9082, OTLP endpoint, log pattern |
| `microservices/distributed-tracing/inventory-service/src/test/resources/application.properties` | Create | Disable sampling in tests |
| `microservices/distributed-tracing/inventory-service/src/test/java/com/example/inventory/InventoryControllerTest.java` | Create | `@WebMvcTest` smoke test |
| `microservices/distributed-tracing/order-service/pom.xml` | Create | Same deps as inventory-service |
| `microservices/distributed-tracing/order-service/src/main/java/com/example/order/OrderServiceApp.java` | Create | Entry point + `RestTemplate` `@Bean` |
| `microservices/distributed-tracing/order-service/src/main/java/com/example/order/OrderController.java` | Create | `GET /orders/{orderId}` — calls inventory via RestTemplate |
| `microservices/distributed-tracing/order-service/src/main/resources/application.yml` | Create | Port 9081, OTLP endpoint, log pattern |
| `microservices/distributed-tracing/order-service/src/test/resources/application.properties` | Create | Disable sampling in tests |
| `microservices/distributed-tracing/order-service/src/test/java/com/example/order/OrderControllerTest.java` | Create | `@WebMvcTest` with `@MockBean RestTemplate` |
| `microservices/distributed-tracing/docker-compose.yml` | Create | OTel Collector + Tempo + Grafana |
| `microservices/distributed-tracing/otel-collector-config.yml` | Create | OTLP receiver → Tempo OTLP exporter |
| `microservices/distributed-tracing/tempo-config.yml` | Create | Tempo local storage config |
| `microservices/distributed-tracing/grafana-datasources.yml` | Create | Auto-provisions Tempo data source |
| `microservices/distributed-tracing/notes.md` | Create | What/Why/How/Code/Interview Angles |
| `microservices/distributed-tracing/index.md` | Create | Topic index |

---

## Task 1: Parent POM

**Files:**
- Create: `microservices/distributed-tracing/pom.xml`

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
    <artifactId>distributed-tracing-demo</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>Distributed Tracing Demo</name>

    <properties>
        <java.version>17</java.version>
    </properties>

    <modules>
        <module>inventory-service</module>
        <module>order-service</module>
    </modules>

</project>
```

- [ ] **Step 2: Commit**

```bash
cd microservices/distributed-tracing
git add pom.xml
git commit -m "feat: scaffold distributed-tracing parent POM"
```

---

## Task 2: inventory-service

**Files:**
- Create: `microservices/distributed-tracing/inventory-service/pom.xml`
- Create: `microservices/distributed-tracing/inventory-service/src/main/java/com/example/inventory/InventoryServiceApp.java`
- Create: `microservices/distributed-tracing/inventory-service/src/main/java/com/example/inventory/InventoryController.java`
- Create: `microservices/distributed-tracing/inventory-service/src/main/resources/application.yml`
- Create: `microservices/distributed-tracing/inventory-service/src/test/resources/application.properties`
- Test: `microservices/distributed-tracing/inventory-service/src/test/java/com/example/inventory/InventoryControllerTest.java`

- [ ] **Step 1: Write the failing test**

`inventory-service/src/test/java/com/example/inventory/InventoryControllerTest.java`:
```java
package com.example.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getStock_returnsStockInfo() throws Exception {
        mockMvc.perform(get("/inventory/item-7"))
               .andExpect(status().isOk())
               .andExpect(content().string("Item item-7: 42 units in stock"));
    }
}
```

`inventory-service/src/test/resources/application.properties` (prevents OTel export during tests):
```properties
management.tracing.sampling.probability=0.0
```

- [ ] **Step 2: Create the POM so the test can compile**

`inventory-service/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>distributed-tracing-demo</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>inventory-service</artifactId>
    <name>Inventory Service</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <!--
            micrometer-tracing-bridge-otel:
            Bridges the Micrometer Tracing facade to the OpenTelemetry SDK.
            Your code calls io.micrometer.tracing.Tracer; the bridge translates
            those calls to OTel's io.opentelemetry.api.trace.Tracer at runtime.
        -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-tracing-bridge-otel</artifactId>
        </dependency>
        <!--
            opentelemetry-exporter-otlp:
            Ships completed spans to an OTel Collector (or directly to Tempo)
            using the OTLP protocol over HTTP/protobuf on port 4318.
        -->
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-otlp</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
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

- [ ] **Step 3: Run test — expect FAIL (class not found)**

```bash
cd microservices/distributed-tracing/inventory-service
mvn test -pl . 2>&1 | tail -20
```

Expected: compilation error — `InventoryController` not found.

- [ ] **Step 4: Implement the service**

`src/main/java/com/example/inventory/InventoryServiceApp.java`:
```java
package com.example.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InventoryServiceApp {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApp.class, args);
    }
}
```

`src/main/java/com/example/inventory/InventoryController.java`:
```java
package com.example.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

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
```

`src/main/resources/application.yml`:
```yaml
server:
  port: 9082

spring:
  application:
    name: inventory-service

management:
  tracing:
    sampling:
      probability: 1.0   # trace every request — dev only, use ~0.01 in prod
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces   # OTel Collector HTTP receiver

# Include traceId and spanId in every log line for log-to-trace correlation
logging:
  pattern:
    level: "%5p [${spring.application.name},%X{traceId:-},%X{spanId:-}]"
```

- [ ] **Step 5: Run test — expect PASS**

```bash
cd microservices/distributed-tracing/inventory-service
mvn test
```

Expected output:
```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 6: Commit**

```bash
git add microservices/distributed-tracing/inventory-service/
git commit -m "feat: add inventory-service with Micrometer+OTel tracing"
```

---

## Task 3: order-service

**Files:**
- Create: `microservices/distributed-tracing/order-service/pom.xml`
- Create: `microservices/distributed-tracing/order-service/src/main/java/com/example/order/OrderServiceApp.java`
- Create: `microservices/distributed-tracing/order-service/src/main/java/com/example/order/OrderController.java`
- Create: `microservices/distributed-tracing/order-service/src/main/resources/application.yml`
- Create: `microservices/distributed-tracing/order-service/src/test/resources/application.properties`
- Test: `microservices/distributed-tracing/order-service/src/test/java/com/example/order/OrderControllerTest.java`

- [ ] **Step 1: Write the failing test**

`order-service/src/test/java/com/example/order/OrderControllerTest.java`:
```java
package com.example.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // RestTemplate is defined in OrderServiceApp, not the controller — mock it
    // so the controller's constructor can be satisfied without a real HTTP call.
    @MockBean
    private RestTemplate restTemplate;

    @Test
    void placeOrder_callsInventoryAndReturnsConfirmation() throws Exception {
        when(restTemplate.getForObject(
                "http://localhost:9082/inventory/order-1", String.class))
            .thenReturn("Item order-1: 42 units in stock");

        mockMvc.perform(get("/orders/order-1"))
               .andExpect(status().isOk())
               .andExpect(content().string(
                   "Order #order-1 confirmed. Stock: Item order-1: 42 units in stock"));
    }
}
```

`order-service/src/test/resources/application.properties`:
```properties
management.tracing.sampling.probability=0.0
```

- [ ] **Step 2: Create the POM**

`order-service/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>distributed-tracing-demo</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>order-service</artifactId>
    <name>Order Service</name>

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
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-tracing-bridge-otel</artifactId>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-otlp</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
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

- [ ] **Step 3: Run test — expect FAIL**

```bash
cd microservices/distributed-tracing/order-service
mvn test 2>&1 | tail -20
```

Expected: compilation error — `OrderController` not found.

- [ ] **Step 4: Implement the service**

`src/main/java/com/example/order/OrderServiceApp.java`:
```java
package com.example.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class OrderServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApp.class, args);
    }

    /**
     * RestTemplateBuilder (auto-configured by Spring Boot) adds a
     * TracingClientHttpRequestInterceptor before build() is called.
     * This interceptor injects the W3C "traceparent" header on every
     * outbound call, propagating the active trace to inventory-service.
     */
    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}
```

`src/main/java/com/example/order/OrderController.java`:
```java
package com.example.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

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
        String stock = restTemplate.getForObject(
            "http://localhost:9082/inventory/" + orderId, String.class);
        return "Order #" + orderId + " confirmed. Stock: " + stock;
    }
}
```

`src/main/resources/application.yml`:
```yaml
server:
  port: 9081

spring:
  application:
    name: order-service

management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces

logging:
  pattern:
    level: "%5p [${spring.application.name},%X{traceId:-},%X{spanId:-}]"
```

- [ ] **Step 5: Run test — expect PASS**

```bash
cd microservices/distributed-tracing/order-service
mvn test
```

Expected:
```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 6: Run all tests from parent**

```bash
cd microservices/distributed-tracing
mvn test
```

Expected: `BUILD SUCCESS` with 2 tests total (1 per module).

- [ ] **Step 7: Commit**

```bash
git add microservices/distributed-tracing/order-service/
git commit -m "feat: add order-service — calls inventory via traced RestTemplate"
```

---

## Task 4: Docker Infrastructure

**Files:**
- Create: `microservices/distributed-tracing/docker-compose.yml`
- Create: `microservices/distributed-tracing/otel-collector-config.yml`
- Create: `microservices/distributed-tracing/tempo-config.yml`
- Create: `microservices/distributed-tracing/grafana-datasources.yml`

- [ ] **Step 1: Create docker-compose.yml**

```yaml
version: '3.8'

# ── Distributed Tracing Infrastructure ────────────────────────────────────────
#
# Data flow:
#   Spring services
#     → OTel Collector  (OTLP HTTP :4318)
#     → Grafana Tempo   (OTLP gRPC :4317, internal)
#     → Grafana UI      (query Tempo at :3200)
#
# Services send to localhost:4318. The Collector forwards to tempo:4317
# (Docker DNS resolves "tempo" inside the compose network).

services:

  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.96.0
    volumes:
      - ./otel-collector-config.yml:/etc/otelcol/config.yml
    command: ["--config=/etc/otelcol/config.yml"]
    ports:
      - "4317:4317"   # OTLP gRPC  (if you prefer gRPC export)
      - "4318:4318"   # OTLP HTTP  (default used by Spring Boot OtlpAutoConfig)
    depends_on:
      - tempo

  tempo:
    image: grafana/tempo:2.4.0
    command: ["-config.file=/etc/tempo/tempo.yml"]
    volumes:
      - ./tempo-config.yml:/etc/tempo/tempo.yml
    ports:
      - "3200:3200"   # Tempo HTTP API — Grafana queries traces here

  grafana:
    image: grafana/grafana:10.2.0
    ports:
      - "3000:3000"
    environment:
      - GF_AUTH_ANONYMOUS_ENABLED=true
      - GF_AUTH_ANONYMOUS_ORG_ROLE=Admin
    volumes:
      - ./grafana-datasources.yml:/etc/grafana/provisioning/datasources/datasources.yml
    depends_on:
      - tempo
```

- [ ] **Step 2: Create otel-collector-config.yml**

```yaml
# OTel Collector pipeline:
#   receivers: accept OTLP from Spring services (gRPC + HTTP)
#   exporters: forward all spans to Tempo via OTLP gRPC
#   service:   wire them into a traces pipeline

receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318   # Spring Boot OtlpAutoConfiguration posts here

exporters:
  otlp:
    endpoint: tempo:4317          # Tempo's OTLP gRPC receiver (compose DNS)
    tls:
      insecure: true              # no TLS in local dev

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlp]
```

- [ ] **Step 3: Create tempo-config.yml**

```yaml
server:
  http_listen_port: 3200   # Grafana queries Tempo on this port

distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317   # OTel Collector pushes spans here

storage:
  trace:
    backend: local              # local disk — fine for demo; use GCS/S3 in prod
    local:
      path: /var/tempo/traces
    wal:
      path: /var/tempo/wal
```

- [ ] **Step 4: Create grafana-datasources.yml**

```yaml
# Auto-provisioned at startup — no manual UI setup required.
apiVersion: 1
datasources:
  - name: Tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    isDefault: true
    jsonData:
      nodeGraph:
        enabled: true       # shows service dependency graph in Grafana
```

- [ ] **Step 5: Start infra and verify**

```bash
cd microservices/distributed-tracing
docker compose up -d
docker compose ps
```

Expected — all three containers in state `running`:
```
NAME                STATUS
...-otel-collector  running
...-tempo           running
...-grafana         running
```

- [ ] **Step 6: Commit**

```bash
git add microservices/distributed-tracing/docker-compose.yml \
        microservices/distributed-tracing/otel-collector-config.yml \
        microservices/distributed-tracing/tempo-config.yml \
        microservices/distributed-tracing/grafana-datasources.yml
git commit -m "feat: add OTel Collector + Tempo + Grafana docker-compose"
```

---

## Task 5: End-to-End Smoke Test (manual)

No test file — this verifies the full trace pipeline with infra running.

- [ ] **Step 1: Start inventory-service (terminal 1)**

```bash
cd microservices/distributed-tracing/inventory-service
mvn spring-boot:run
```

Wait for: `Started InventoryServiceApp in ... seconds`

- [ ] **Step 2: Start order-service (terminal 2)**

```bash
cd microservices/distributed-tracing/order-service
mvn spring-boot:run
```

Wait for: `Started OrderServiceApp in ... seconds`

- [ ] **Step 3: Trigger a traced request**

```bash
curl -s http://localhost:9081/orders/item-1
```

Expected response:
```
Order #item-1 confirmed. Stock: Item item-1: 42 units in stock
```

- [ ] **Step 4: Verify traceId appears in logs**

In order-service terminal, look for a log line like:
```
 INFO [order-service,4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7] ...
```
The second field is the **traceId**. Copy it.

- [ ] **Step 5: Find the trace in Grafana**

1. Open http://localhost:3000
2. Left sidebar → **Explore** → select **Tempo** data source
3. Query type: **Search** → Service Name: `order-service` → **Run query**
4. Click any trace row → the flame graph shows two spans:
   - `GET /orders/{orderId}` (order-service)
   - `GET /inventory/{itemId}` (inventory-service, child span)

Both spans share the same **Trace ID**, confirming end-to-end context propagation.

---

## Task 6: Concept Notes

**Files:**
- Create: `microservices/distributed-tracing/notes.md`
- Create: `microservices/distributed-tracing/index.md`

- [ ] **Step 1: Create notes.md**

```markdown
# Distributed Tracing — Micrometer + OpenTelemetry + Grafana

## What

Distributed tracing tracks a single request as it flows through multiple
services. It assigns a single **trace ID** to the whole request and breaks
execution into **spans** — one per service hop. You reconstruct the complete
call chain from trace data.

Key identifiers:
- **Trace ID** — globally unique, shared across all services for one request.
- **Span ID** — unique per unit of work within a trace.
- **Parent span ID** — links a child span to its caller.

## Why

Logs answer "what happened inside service X?" Tracing answers "what happened
across all services for request Y?". Without tracing, debugging a latency spike
across 10 services means manually correlating timestamps in separate log files.

Tracing also reveals the *shape* of a request: which service is the bottleneck,
which calls are parallel vs. sequential, which spans fail.

## How

### Component roles

| Component | Role |
|-----------|------|
| **Micrometer Tracing** | Vendor-neutral tracing facade (like SLF4J for logging). Your code calls `Tracer` / `Observation` APIs — not OTel directly. |
| **micrometer-tracing-bridge-otel** | Translates Micrometer's API calls to the OpenTelemetry SDK at runtime. Drop this in and your code is OTel-instrumented without touching it. |
| **opentelemetry-exporter-otlp** | Serialises completed spans and ships them via OTLP (OpenTelemetry Protocol) over HTTP/protobuf on port 4318. |
| **OTel Collector** | Receives telemetry, applies processing (batching, sampling, enrichment), then fans out to one or more backends. Decouples services from backend choice. |
| **Grafana Tempo** | Distributed tracing backend: stores spans, indexes by trace ID and service, serves queries. |
| **Grafana** | UI layer: search traces, view flame graphs, explore service dependency graphs. |

### Context propagation

Spring Boot auto-configures `W3CTraceContextPropagator`. Every outbound
`RestTemplate` call (when built via `RestTemplateBuilder`) injects a
`traceparent` HTTP header:

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
              ^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^ ^^
              version   trace-id (128-bit hex)    parent-span-id   flags
```

The downstream service extracts `traceparent`, continues the same trace, and
creates a child span with the upstream span as its parent.

### Sampling

`management.tracing.sampling.probability=1.0` captures every request. In
production lower this (0.01 = 1%) — tracing every request at scale adds CPU,
memory, and network overhead and generates enormous data volumes.

### Log correlation

```yaml
logging:
  pattern:
    level: "%5p [${spring.application.name},%X{traceId:-},%X{spanId:-}]"
```

Micrometer adds `traceId` and `spanId` to MDC automatically. Every log line
now includes the trace ID — paste it into Grafana Tempo's TraceQL search to
jump directly from a log entry to the full distributed trace.

## Code Example

See `order-service/` and `inventory-service/`.

```bash
# 1. Start infra
docker compose up -d

# 2. Start services
cd inventory-service && mvn spring-boot:run &
cd order-service    && mvn spring-boot:run &

# 3. Trigger a trace
curl http://localhost:9081/orders/item-1

# 4. Open Grafana: http://localhost:3000
#    Explore → Tempo → Search → service.name = order-service
```

The flame graph shows two spans: order-service (parent) → inventory-service
(child), both sharing the same trace ID.

## Interview Angles

**Q: What are the three pillars of observability?**
A: Metrics, logs, traces. Metrics = aggregates over time (latency p99, error
rate). Logs = discrete events per service. Traces = correlated call chains
across services. They complement each other: traces have the context, logs have
the detail, metrics drive alerts.

**Q: What is a span? What is a trace?**
A: A span is one unit of work: a service handling a request, or a DB query. It
has a start time, duration, status, and key-value attributes. A trace is the
collection of all spans sharing the same trace ID — the full picture of one
end-to-end request.

**Q: How does trace context cross service boundaries?**
A: Via the `traceparent` HTTP header (W3C Trace Context spec, RFC 7230). The
upstream service injects it; the downstream service extracts it and creates a
child span referencing the upstream span ID. Spring Boot + Micrometer handle
injection and extraction automatically through the `RestTemplate` interceptor
added by `RestTemplateBuilder`.

**Q: Why Micrometer Tracing instead of the OTel API directly?**
A: Vendor neutrality — same reason you use SLF4J instead of Log4j directly.
Your application code calls Micrometer's `Tracer` / `Observation` API. The
bridge (OTel or Brave) is a runtime dependency you swap without touching
application code. Libraries that instrument themselves through Micrometer work
with any backend automatically.

**Q: What is sampling and why does it matter in production?**
A: Sampling controls what fraction of traces are actually recorded. At high
throughput (10k req/s) recording 100% would generate terabytes of data and add
measurable latency overhead. Head-based sampling (decide at trace start, before
seeing the full trace) is simple and supported by Micrometer. Tail-based
sampling (decide after the full trace arrives, so you can keep slow/error traces
selectively) requires a stateful collector like the OTel Collector with the
tail-sampling processor.

**Q: What does the OTel Collector add — can't services export directly to Tempo?**
A: Services *can* export directly (change the endpoint to `tempo:4317`). The
Collector adds: fan-out (one pipeline → Tempo + Jaeger + Prometheus), tail-based
sampling, batching + retry, attribute enrichment (add k8s pod/namespace labels),
and decoupling (swap the backend without redeploying services). In production
Kubernetes environments the Collector runs as a DaemonSet sidecar, so services
just send to `localhost`.
```

- [ ] **Step 2: Create index.md**

```markdown
# Distributed Tracing

End-to-end tracing demo: two Spring Boot services instrumented with Micrometer
Tracing (OpenTelemetry bridge). Traces ship via OTLP to an OTel Collector,
stored in Grafana Tempo, and visualised in Grafana.

## Files

| File | Description |
|------|-------------|
| `notes.md` | Concept reference — What / Why / How / Interview Angles |
| `pom.xml` | Parent POM (Spring Boot 3.2.5, two modules) |
| `order-service/` | Receives orders, calls inventory (port 9081) |
| `inventory-service/` | Returns stock info (port 9082) |
| `docker-compose.yml` | OTel Collector + Grafana Tempo + Grafana |
| `otel-collector-config.yml` | Pipeline: OTLP in (4318) → Tempo OTLP out (4317) |
| `tempo-config.yml` | Tempo local-disk storage |
| `grafana-datasources.yml` | Auto-provisions Tempo data source in Grafana |

## Quick Start

```bash
# 1. Start infra (OTel Collector, Tempo, Grafana)
docker compose up -d

# 2. Start services (separate terminals)
cd inventory-service && mvn spring-boot:run
cd order-service    && mvn spring-boot:run

# 3. Trigger a trace
curl http://localhost:9081/orders/item-1

# 4. View in Grafana
#    http://localhost:3000 → Explore → Tempo → Search → service.name = order-service
```
```

- [ ] **Step 3: Commit**

```bash
git add microservices/distributed-tracing/notes.md \
        microservices/distributed-tracing/index.md
git commit -m "docs: add distributed-tracing notes and index"
```

---

## Self-Review

**Spec coverage:**
- [x] Micrometer Tracing — `micrometer-tracing-bridge-otel` in both POMs; Micrometer facade used for tracing
- [x] OpenTelemetry — bridge + OTLP exporter; `traceparent` propagation via `RestTemplateBuilder`
- [x] Grafana — docker-compose with Grafana + Tempo + auto-provisioned datasource
- [x] Two-service chain — order-service calls inventory-service, creating a parent→child span tree
- [x] Tests — `@WebMvcTest` for both controllers; test properties disable sampling
- [x] Log correlation — `%X{traceId}` pattern in both service YAMLs
- [x] Concept notes — all five sections, all interview questions covered

**Placeholder scan:** No TBDs, no "implement later", no "similar to Task N" references. All code is complete.

**Type consistency:** `getForObject("http://localhost:9082/inventory/" + orderId, String.class)` in OrderController matches `@GetMapping("/inventory/{itemId}")` in InventoryController. Response format `"Item " + itemId + ": 42 units in stock"` matches the mock in `OrderControllerTest`.
