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

### Is it automatic?

Yes, mostly. Once the dependencies are added:

- **Automatic**: 
    - Creating Trace IDs and Span IDs for incoming HTTP requests.
    - Propagating headers to downstream services (if using `RestTemplateBuilder` or `WebClient.Builder`).
    - Correlating logs (adding IDs to your console logs automatically via MDC).
    - Creating spans for common operations like JDBC queries or many spring-cloud integrations.

- **Manual**:
    - **Internal Spans**: If you want to trace a specific slow method *inside* your service, you use the `@Observed` annotation or the `Tracer` API.
    - **Manual Client Instantiation**: If you use `new RestTemplate()` instead of the builder, the tracing interceptor isn't added, and the "chain" will break.

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
             version   trace-id (128-bit hex)    parent-id (W3C spec name)   flags
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
    console: "%d{HH:mm:ss.SSS} %5p [${spring.application.name},%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n"
```

Micrometer adds `traceId` and `spanId` to MDC automatically. Every log line
now includes the trace ID — paste it into Grafana Tempo's TraceQL search to
jump directly from a log entry to the full distributed trace.

## Advanced Concepts

### Baggage vs. Tags

- **Tags (Attributes)**: Key-value pairs attached to a **specific span**. They are used for searching/filtering *within* a backend like Tempo (e.g., `http.method=GET`, `error=true`). They do **not** propagate to other services.
- **Baggage**: Key-value pairs that travel **across the entire trace**. If Service A sets baggage `user-id: 99`, Service Z (six hops later) can read it. 
    - *Warning:* Baggage is sent in HTTP headers (using the `baggage` header). Overusing it increases network overhead for *every* call in the system.

### Messaging (Kafka / RabbitMQ)

Tracing isn't just for REST. In message-driven systems:
1. The **Producer** injects the trace context into the **Message Headers** (not the payload).
2. The **Consumer** extracts the context and starts a child span.
3. This allows you to see the "gap" or lag between when a message was produced and when it was consumed in the same flame graph.

### The Thread-Loss Problem (Async)

Tracing context is stored in `ThreadLocal`. If you use `@Async`, `CompletableFuture`, or custom thread pools, the trace ID is **lost** because it doesn't automatically move to the new thread.
- *Fix:* Use `ContextPropagatingExecutorService` or decorate your `TaskExecutor` with a `DelegatingSecurityContextAsyncTaskExecutor` equivalent for observations.

## Code Example

See `order-service/` and `inventory-service/`.

```bash
# 1. Start infra
docker compose up -d

# 2. Start services
(cd inventory-service && mvn spring-boot:run) &
(cd order-service    && mvn spring-boot:run) &

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
A: Via the `traceparent` HTTP header (W3C Trace Context Recommendation). The
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

**Q: What happens if a load balancer or API gateway strips the `traceparent` header?**
A: The downstream service sees no incoming trace context, so it starts a brand-new root
span with a fresh trace ID. This silently breaks the trace chain — the upstream and
downstream spans become two unrelated traces in Grafana Tempo. Mitigation: configure
the gateway to pass through `traceparent` (and `tracestate`) headers. Spring Cloud
Gateway preserves them by default; NGINX requires an explicit `proxy_set_header
traceparent $http_traceparent;` directive. This is why gateway configuration is the
first thing to check when traces appear truncated in Tempo.

**Q: What is the difference between Tags and Baggage?**
A: Tags are local to a span; Baggage is global to a trace. Use Tags for filtering a specific service's spans. Use Baggage for information that must be visible across service boundaries (like a `customerId` or `tenantId`) without passing it through every method signature.

**Q: How do you handle tracing in an asynchronous environment (e.g., @Async)?**
A: Since tracing context is stored in `ThreadLocal`, it is lost when moving to a new thread. You must wrap your Executor in a `ContextPropagatingExecutorService` provided by Micrometer, which ensures the `Observation` context is "snapped" from the parent thread and restored in the child thread.

**Q: Can you trace a request through a Message Broker like Kafka?**
A: Yes. Modern instrumentation (Micrometer/OTel) uses **Header Injection**. The trace context is added to the Kafka Message Headers. The consumer reads these headers to re-establish the trace context. This allows you to measure "async latency" (the time a message sat in the broker).
