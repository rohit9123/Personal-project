# Distributed Tracing

End-to-end tracing demo: two Spring Boot services instrumented with Micrometer
Tracing (OpenTelemetry bridge). Traces ship via OTLP to an OTel Collector,
stored in Grafana Tempo, and visualised in Grafana.

## Files

| File | Description |
|------|-------------|
| [notes.md](notes.md) | Concept reference — What / Why / How / Interview Angles |
| [pom.xml](pom.xml) | Parent POM (Spring Boot 3.2.5, two modules) |
| [order-service/](order-service/) | Receives orders, calls inventory (port 9081) |
| [inventory-service/](inventory-service/) | Returns stock info (port 9082) |
| [docker-compose.yml](docker-compose.yml) | OTel Collector + Grafana Tempo + Grafana |
| [otel-collector-config.yml](otel-collector-config.yml) | Pipeline: OTLP in (4318) → batch → Tempo OTLP out (4317) |
| [tempo-config.yml](tempo-config.yml) | Tempo local-disk storage |
| [grafana-datasources.yml](grafana-datasources.yml) | Auto-provisions Tempo data source in Grafana |

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
