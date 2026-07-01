# MDC — Mapped Diagnostic Context

## What

**MDC** (Mapped Diagnostic Context) is a **thread-local `Map<String, String>`** managed by SLF4J/Logback. Values placed into it are automatically attached to every log event emitted on that thread — no need to pass them explicitly to each `log.info(...)` call.

```
Request arrives on Thread-A
       │
       ▼
MDC.put("requestId", "abc123")   ← stored in Thread-A's local map
       │
  ... business logic ...
  log.info("Processing")         ← Logback reads MDC, appends requestId automatically
  log.debug("Fetching from DB")  ← same map, same thread
       │
       ▼
MDC.clear()                      ← MUST clear before thread returns to pool
```

---

## Why

Without MDC, correlating all log lines for a single request requires embedding IDs in every `log.xxx()` call:

```java
log.info("Processing order {} for user {}", orderId, userId);   // repeated everywhere
```

MDC sets these values once (at request entry) and they appear on every log line automatically.

---

## How

### 1 — Set and Clear

```java
import org.slf4j.MDC;

// In a servlet filter / Spring interceptor
MDC.put("requestId", UUID.randomUUID().toString());
MDC.put("userId", authenticatedUser.getId());

try {
    chain.doFilter(request, response);   // all logs on this thread carry requestId + userId
} finally {
    MDC.clear();   // ← CRITICAL — see Problem 1 below
}
```

### 2 — Include MDC in the log pattern

```properties
# application.properties
logging.pattern.console=%d{HH:mm:ss.SSS} %-5level [%thread] [%X{requestId}] %logger{36} - %msg%n
```

Or in `logback-spring.xml`:

```xml
<pattern>%d{HH:mm:ss.SSS} %-5level [%X{requestId}] %logger{36} - %msg%n</pattern>
```

`%X{key}` reads the MDC value for `key` at format time.

### 3 — Filter / Interceptor pattern (Spring MVC)

```java
@Component
public class MdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            MDC.put("requestId", UUID.randomUUID().toString());
            MDC.put("method", req.getMethod());
            MDC.put("uri", req.getRequestURI());
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
```

---

## Problem 1 — Log Pollution (missing MDC.clear())

### The bug

Spring Boot uses a **thread pool** (e.g., Tomcat's `http-nio-8080-exec-*` pool). After a request finishes, the thread returns to the pool and picks up the *next* request — **without resetting its MDC**.

```
Request-1 → Thread-5 → MDC.put("userId", "u1")
                      → [MDC NOT cleared]
                      → Thread-5 returns to pool

Request-2 → Thread-5 → MDC still has "userId=u1" ← WRONG USER's ID appears in Request-2's logs!
```

**Consequence:** request-2's logs are tagged with request-1's `userId`. In a security audit this looks like user-1 performed user-2's actions.

### The fix

Always `MDC.clear()` in a `finally` block:

```java
try {
    MDC.put("userId", userId);
    // ... handle request ...
} finally {
    MDC.clear();   // guaranteed to run even on exception
}
```

**Why `finally` and not after the business logic?**
Because exceptions thrown inside the try block would skip a plain `MDC.clear()` placed at the end.

---

## Problem 2 — Async Calls Lose MDC

### The bug

MDC is **thread-local**. When you hand off work to a different thread (via `@Async`, `CompletableFuture.supplyAsync`, etc.), the new thread has an **empty MDC**:

```java
@Service
public class OrderService {

    @Async
    public CompletableFuture<Void> processAsync(String orderId) {
        // ← MDC is EMPTY here — different thread from the HTTP handler
        log.info("Processing order {}", orderId);   // no requestId in this log!
        return CompletableFuture.completedFuture(null);
    }
}
```

Log output:
```
[http-nio-exec-1] [requestId=abc123] OrderController - Received order
[task-1]          []                 OrderService   - Processing order 42   ← requestId missing!
```

### Why this happens

`MDC.put` stores data in a `ThreadLocal`. `@Async` uses Spring's `TaskExecutor` which pulls a thread from a pool. That thread has no knowledge of the HTTP thread's `ThreadLocal` storage.

---

## Problem 2 Solution — TaskDecorator

Spring's `TaskExecutor` accepts a **`TaskDecorator`** — a wrapper around each `Runnable` that runs on the *calling* thread before the task is submitted, giving you access to the current thread's MDC. You capture the MDC snapshot there and restore it inside the task thread.

```java
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture MDC from the CALLING thread (e.g., HTTP thread)
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        return () -> {
            try {
                // Restore the captured MDC on the WORKER thread
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                runnable.run();
            } finally {
                MDC.clear();   // clean up the worker thread's MDC after task
            }
        };
    }
}
```

Wire it into Spring's async executor:

```java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(new MdcTaskDecorator());   // ← wire it here
        executor.initialize();
        return executor;
    }
}
```

Now `@Async` methods inherit the caller's MDC automatically:

```
[http-nio-exec-1] [requestId=abc123] OrderController - Received order
[async-1]         [requestId=abc123] OrderService   - Processing order 42   ← propagated!
```

---

## Interview Angles — MDC

**Q: What is MDC and why is it useful in web applications?**
A: MDC is a thread-local key-value map that Logback reads when formatting each log event. In a web app you set `requestId`, `userId`, `traceId` once in a filter at request entry — every log statement on that thread then carries those values automatically. This eliminates the noise of passing contextual IDs through every method signature, and lets you filter all logs for a single request in a log aggregator with a single query like `requestId:"abc123"`.

**Q: What is log pollution and how does MDC.clear() prevent it?**
A: Log pollution is when one request's contextual data (e.g., `userId`) appears in another request's log lines because the thread was reused without clearing its MDC. Web servers pool threads; after request-1 finishes, if `MDC.clear()` is not called, the thread's MDC still holds request-1's values when it picks up request-2. Calling `MDC.clear()` in a `finally` block in the filter guarantees the MDC is wiped before the thread returns to the pool.

**Q: Why does MDC not propagate to @Async threads, and how do you fix it?**
A: MDC uses `ThreadLocal` storage, which is per-thread. When `@Async` schedules a task on a thread-pool thread, that thread has no knowledge of the HTTP thread's `ThreadLocal`. The fix is a `TaskDecorator`: before the task is submitted, capture `MDC.getCopyOfContextMap()` on the calling thread; inside the decorated `Runnable`, call `MDC.setContextMap(captured)` to restore it on the worker thread, then `MDC.clear()` in `finally`. Spring's `ThreadPoolTaskExecutor` accepts a `TaskDecorator` via `setTaskDecorator(...)`.

**Q: What is MDC.getCopyOfContextMap() vs MDC.getContextMap()?**
A: `getCopyOfContextMap()` returns a snapshot (`Map<String, String>`) that is disconnected from the thread-local — safe to hand to another thread. `getContextMap()` in older SLF4J versions could return the live map, which would be a race condition if passed across threads. Always use `getCopyOfContextMap()` when propagating MDC across thread boundaries.

---

# Correlation ID

## What

A **Correlation ID** (also called Request ID or Trace ID in simpler systems) is a **unique identifier** assigned to a request at the system boundary and **propagated through every service** that handles the request. It ties together all log lines, across all services, that belong to the same user-initiated action.

```
Client → [API Gateway] → [Order Service] → [Inventory Service] → [Notification Service]
              ↑
   correlationId = "req-7f3a9b" injected here
              │
              ├── Order Service logs:      [correlationId=req-7f3a9b] ...
              ├── Inventory Service logs:  [correlationId=req-7f3a9b] ...
              └── Notification logs:       [correlationId=req-7f3a9b] ...
```

Without a Correlation ID, when a request fails you have logs scattered across multiple services with no common key — you cannot reconstruct the request's journey.

---

## Why

| Problem without Correlation ID | How Correlation ID solves it |
|-------------------------------|------------------------------|
| Cannot find all logs for one request | Filter by `correlationId` across all services |
| Cannot determine which service failed | Logs reveal the last service to log with that ID |
| Impossible to measure true end-to-end latency | Timestamps on first and last log with same ID |
| Support tickets have no actionable log query | User provides their `correlationId` from error response |

---

## How

### 1 — Generate or accept Correlation ID at the entry point

Convention: honour a Correlation ID from the incoming HTTP header if present (so clients / API Gateways can inject one); otherwise generate a fresh UUID.

```java
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = req.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        res.addHeader(HEADER, correlationId);   // echo back so caller can log it too

        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
```

### 2 — Include Correlation ID in log pattern

```properties
logging.pattern.console=%d{HH:mm:ss.SSS} %-5level [%X{correlationId}] %logger{36} - %msg%n
```

### 3 — Propagate Correlation ID to downstream HTTP calls

When calling another microservice, read the Correlation ID from MDC and set it as an HTTP header:

```java
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setInterceptors(List.of(new CorrelationIdInterceptor()));
        return restTemplate;
    }
}

public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest req, byte[] body,
                                        ClientHttpRequestExecution exec) throws IOException {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            req.getHeaders().add("X-Correlation-ID", correlationId);
        }
        return exec.execute(req, body);
    }
}
```

### 4 — Combine with TaskDecorator for async calls

The `MdcTaskDecorator` from the MDC section automatically propagates `correlationId` to `@Async` threads since it copies the entire MDC map.

---

## Interview Angles — Correlation ID

**Q: What is a Correlation ID and why is it essential in microservices?**
A: A Correlation ID is a UUID (or similar unique token) assigned at the system entry point and propagated via HTTP headers to every downstream service. It becomes a key in each service's MDC so every log line is tagged with it. Without it, debugging a cross-service failure requires guessing which request caused which log line across services — impossible at scale. With it, a single `correlationId` query in a log aggregator retrieves the complete audit trail of one user's request across all services.

**Q: Should you generate a new Correlation ID or accept one from the client?**
A: Accept from the client (e.g., `X-Correlation-ID` header) if present, otherwise generate one. This lets upstream systems (API Gateways, mobile apps) inject their own IDs for end-to-end tracing that spans beyond your backend. Validate the incoming value (length, format) to prevent header injection. Echo it back in the response so the client can include it in bug reports.

**Q: How do you propagate Correlation ID across microservices?**
A: Three points of propagation: (1) **inbound** — read from `X-Correlation-ID` HTTP header in a `OncePerRequestFilter`, store in MDC; (2) **outbound HTTP** — use a `ClientHttpRequestInterceptor` on `RestTemplate` (or `ExchangeFilterFunction` on `WebClient`) to read from MDC and set the header on every outgoing request; (3) **async tasks** — use `MdcTaskDecorator` to copy MDC to worker threads. All three together mean the ID flows everywhere without manual passing.

---

# End-to-End Distributed Logging with TraceID and CorrelationID

## What

**End-to-end distributed logging** is the practice of attaching identifiers to every log line such that you can reconstruct the complete journey of a request across all services, threads, and async tasks. Two complementary IDs are typically used:

| ID | Scope | Who generates | Purpose |
|----|-------|---------------|---------|
| **Correlation ID** | Business / user request | Entry filter or API Gateway | Tie all logs for one user action together |
| **Trace ID** | Distributed trace (spans) | OpenTelemetry / Micrometer Tracing | Feed into distributed tracing systems (Jaeger, Zipkin) |

In practice, Spring Boot's Micrometer Tracing auto-populates `traceId` and `spanId` into MDC, and you add your `correlationId` on top. Together they give both business-level and infrastructure-level observability.

---

## Why

| Observability need | Tool |
|-------------------|------|
| "Show me all logs for order #42's checkout request" | `correlationId` filter in Kibana / Loki |
| "Which service is the latency bottleneck?" | `traceId` in Jaeger / Zipkin — shows span durations |
| "Which async thread processed this?" | `spanId` differentiates child spans from parent |
| "What did user-1 do in the last 5 minutes?" | `userId` + `correlationId` in MDC |

---

## How

### Full Stack — dependencies

```xml
<!-- Spring Boot 3.x — Micrometer Tracing (auto-injects traceId/spanId into MDC) -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>

<!-- JSON logs with all MDC fields -->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

`application.properties`:

```properties
spring.application.name=order-service
management.tracing.sampling.probability=1.0   # sample 100% in dev; tune in prod
```

### CorrelationId filter (same as above, now combined with traceId)

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = req.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put("correlationId", correlationId);
        res.addHeader(HEADER, correlationId);

        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
            // Note: Micrometer Tracing manages traceId/spanId — don't clear those manually
        }
    }
}
```

### logback-spring.xml — JSON output with all IDs

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <springProperty scope="context" name="appName" source="spring.application.name"/>

    <!-- Dev: human-readable with all IDs visible -->
    <springProfile name="dev">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>
                    %d{HH:mm:ss.SSS} %-5level [%thread]
                    [traceId=%X{traceId}] [spanId=%X{spanId}] [correlationId=%X{correlationId}]
                    %logger{36} - %msg%n
                </pattern>
            </encoder>
        </appender>
        <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    </springProfile>

    <!-- Prod: structured JSON — every MDC field becomes a top-level JSON key -->
    <springProfile name="prod">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <!-- Rename default @timestamp to timestamp if needed -->
                <fieldNames><timestamp>timestamp</timestamp></fieldNames>
                <!-- Add service name as a static field -->
                <customFields>{"service":"${appName}"}</customFields>
            </encoder>
        </appender>
        <root level="INFO"><appender-ref ref="JSON"/></root>
    </springProfile>

</configuration>
```

**Sample prod JSON output:**

```json
{
  "timestamp": "2025-04-25T10:30:00.123Z",
  "level": "INFO",
  "thread_name": "http-nio-8080-exec-3",
  "logger_name": "com.example.OrderService",
  "message": "Order 42 placed successfully",
  "service": "order-service",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "correlationId": "req-7f3a9b12-c4d1-4e5f-a8b9-1234567890ab"
}
```

### Propagating both IDs to downstream services

```java
public class DistributedContextInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest req, byte[] body,
                                        ClientHttpRequestExecution exec) throws IOException {
        // Correlation ID — business-level context
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            req.getHeaders().add("X-Correlation-ID", correlationId);
        }
        // B3 tracing headers (traceId, spanId) are propagated automatically
        // by Micrometer Tracing's RestTemplate instrumentation — no manual work needed
        return exec.execute(req, body);
    }
}
```

> **Note:** With `micrometer-tracing-bridge-brave`, Spring auto-instruments `RestTemplate` and `WebClient` to propagate B3 headers (`X-B3-TraceId`, `X-B3-SpanId`, `traceparent`). You only need the interceptor for `correlationId`.

### Full picture — what each service must do

```
┌─────────────────────────────────────────────────────┐
│  EACH MICROSERVICE                                  │
│                                                     │
│  1. OncePerRequestFilter                            │
│     - Read X-Correlation-ID header (or generate)   │
│     - MDC.put("correlationId", id)                 │
│     - Echo header back in response                 │
│     - MDC.clear() in finally                       │
│                                                     │
│  2. Micrometer Tracing (auto)                      │
│     - MDC.put("traceId", ...)                      │
│     - MDC.put("spanId", ...)                       │
│     - Propagates B3 headers on outbound calls      │
│                                                     │
│  3. MdcTaskDecorator on ThreadPoolTaskExecutor     │
│     - Copies full MDC to @Async worker threads     │
│                                                     │
│  4. logback-spring.xml                             │
│     - %X{traceId} %X{spanId} %X{correlationId}    │
│     - LogstashEncoder in prod (JSON)               │
└─────────────────────────────────────────────────────┘
```

### Log aggregator query examples (Kibana / Grafana Loki)

```
# All logs for one user request across all services
correlationId: "req-7f3a9b12-c4d1-4e5f-a8b9-1234567890ab"

# All logs for one distributed trace (Zipkin-style)
traceId: "4bf92f3577b34da6a3ce929d0e0e4736"

# Errors in order-service for a specific user today
service: "order-service" AND level: "ERROR" AND userId: "u1"
```

---

## Interview Angles — End-to-End Distributed Logging

**Q: What is the difference between a Correlation ID and a Trace ID?**
A: A Correlation ID is a business-level identifier you assign at the user request boundary — it ties together all log lines across all services for one user-initiated action. A Trace ID is an infrastructure-level identifier generated by a distributed tracing system (OpenTelemetry, Brave/Zipkin) — it covers the same request but enables span-level latency profiling and dependency graph visualisation in tools like Jaeger. In practice both coexist in MDC: `traceId` feeds the tracing backend, `correlationId` is what you give to support teams or expose in error responses.

**Q: How do you achieve end-to-end log correlation in a Spring Boot microservices system?**
A: Three layers: (1) **MDC at request entry** — a `OncePerRequestFilter` puts `correlationId` (and optionally `userId`) into MDC, cleared in `finally`. (2) **Micrometer Tracing** auto-populates `traceId` and `spanId` into MDC and propagates B3 headers on outbound `RestTemplate`/`WebClient` calls. (3) **MdcTaskDecorator** copies the MDC snapshot to async threads. With `LogstashEncoder` in prod, every log line is a JSON object with all four IDs as queryable fields.

**Q: What HTTP header should carry the Correlation ID, and who owns its format?**
A: The de facto convention is `X-Correlation-ID` (a custom `X-` header). There is no RFC standard, but it is widely used. The entry-point service (API Gateway or the first microservice) generates it if absent. Each service must read it, put it in MDC, and forward it on outgoing HTTP calls. The format is typically a UUID v4 string.

**Q: How does LogstashEncoder make MDC values queryable in production?**
A: `LogstashEncoder` serialises each log event as a JSON object. It automatically includes all MDC entries as top-level JSON fields. This means `correlationId`, `traceId`, `spanId`, `userId` — whatever is in MDC — become individually queryable fields in Elasticsearch/Kibana or Loki/Grafana without any log parsing or regex extraction.

**Q: What happens to traceId and correlationId when an @Async method is called?**
A: Without intervention both are lost — `@Async` runs on a different thread with an empty MDC. The fix is `MdcTaskDecorator`: it captures `MDC.getCopyOfContextMap()` on the HTTP thread before the task is submitted, then calls `MDC.setContextMap(captured)` inside the `Runnable` before `run()`. The worker thread then has the full MDC including `traceId` and `correlationId`. Micrometer Tracing additionally wraps the `Runnable` with span context via its own thread-local propagation.
