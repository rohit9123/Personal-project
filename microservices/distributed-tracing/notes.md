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
