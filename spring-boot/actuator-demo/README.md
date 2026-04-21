# Spring Boot Actuator

---

## 1. What

**Spring Boot Actuator** adds production-ready HTTP endpoints to your application that expose its internal state — health, metrics, configuration, beans, thread state, log levels — without writing any code. Add the dependency and the endpoints appear automatically.

Base URL: `http://localhost:8080/actuator`

Key endpoints:

| Endpoint | Method | What it shows |
|---|---|---|
| `/actuator/health` | GET | App health + all component statuses |
| `/actuator/info` | GET | App metadata (version, team, custom data) |
| `/actuator/env` | GET | Every property source and its values |
| `/actuator/metrics` | GET | List of all metric names |
| `/actuator/metrics/{name}` | GET | Value of a specific metric |
| `/actuator/loggers` | GET | All loggers and their current levels |
| `/actuator/loggers/{name}` | POST | Change a logger's level **at runtime** |
| `/actuator/beans` | GET | Every Spring bean in the context |
| `/actuator/mappings` | GET | Every HTTP endpoint registered in the app |
| `/actuator/configprops` | GET | All `@ConfigurationProperties` beans |
| `/actuator/threaddump` | GET | Current thread stack traces |
| `/actuator/conditions` | GET | Auto-configuration report (matched / not matched) |
| `/actuator/shutdown` | POST | Gracefully shut down the app (disabled by default) |

---

## 2. Why

In production you need to answer questions like:
- Is this service up? Which component is making it unhealthy?
- Why is this property not taking effect?
- Which HTTP endpoints does this service expose?
- Why is there a memory spike right now?
- Can I turn on DEBUG logging for 5 minutes without restarting?

Without Actuator you would need to add custom code for each of these. Actuator gives you all of them out of the box.

---

## 3. How It Works

### Adding Actuator

Just one dependency — no code needed:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

By default only `/actuator/health` and `/actuator/info` are exposed over HTTP (security-first default). You expose more in `application.yml`.

### Exposing endpoints

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "*"                  # all endpoints
        # include: health, info, metrics, loggers   # production-safe subset
        # exclude: shutdown, threaddump             # keep sensitive ones off
```

Two concepts: **enabled** (does the endpoint exist?) vs **exposed** (is it reachable over HTTP?).
Most endpoints are enabled by default; only `health` and `info` are exposed by default.
`shutdown` is disabled by default — you must both enable AND expose it.

### /actuator/health — anatomy

```json
{
  "status": "UP",
  "components": {
    "diskSpace": {
      "status": "UP",
      "details": { "total": 250685575168, "free": 120000000000 }
    },
    "paymentService": {
      "status": "UP",
      "details": { "responseTime": "8ms", "status": "reachable" }
    },
    "ping": { "status": "UP" }
  }
}
```

Overall status is the **worst** of all components: if any component is DOWN, the whole app is DOWN.

Built-in health indicators: `diskSpace`, `db` (if datasource present), `redis`, `rabbit`, `mongo`, etc.
Custom ones: implement `HealthIndicator` and annotate with `@Component`.

### Custom HealthIndicator

```java
@Component
public class PaymentServiceHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        if (isReachable()) {
            return Health.up()
                    .withDetail("responseTime", "8ms")
                    .build();
        }
        return Health.down()
                .withDetail("error", "connection refused")
                .build();
    }
}
```

Spring names it by stripping `HealthIndicator` from the class name: `PaymentServiceHealthIndicator` → `"paymentService"` in the JSON.

### /actuator/loggers — runtime log level change

This is one of the most useful features in production. No restart, no redeploy.

```bash
# See current level for your package
GET /actuator/loggers/com.example

# Change to DEBUG at runtime
POST /actuator/loggers/com.example
Content-Type: application/json
{ "configuredLevel": "DEBUG" }

# Revert to INFO when done
POST /actuator/loggers/com.example
{ "configuredLevel": "INFO" }
```

### /actuator/metrics — drill-down pattern

```bash
# Step 1: list all metric names
GET /actuator/metrics
# → ["jvm.memory.used", "jvm.gc.pause", "http.server.requests", ...]

# Step 2: drill into a specific metric
GET /actuator/metrics/http.server.requests
# → count, sum, max broken down by uri, method, status

# Step 3: filter by tag
GET /actuator/metrics/http.server.requests?tag=status:200
```

### Kubernetes liveness vs readiness probes

```
Liveness  (/actuator/health/liveness)
  → "Is the process alive and not deadlocked?"
  → If DOWN → K8s kills and restarts the pod

Readiness (/actuator/health/readiness)
  → "Is the app ready to accept traffic?"
  → If DOWN → K8s removes pod from load balancer (no kills)
              (used during startup, maintenance, or when a dependency is down)
```

Enable in `application.yml`:
```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

---

## 4. Code

| File | What it is |
|---|---|
| `ActuatorDemoApp.java` | Plain `@SpringBootApplication` — nothing extra needed |
| `PaymentServiceHealthIndicator.java` | Custom `HealthIndicator` — adds `"paymentService"` to `/actuator/health` |
| `AppInfoContributor.java` | `InfoContributor` — adds JVM stats + team info to `/actuator/info` |
| `OrderController.java` | Business endpoints so `/actuator/mappings` is interesting; also has break/fix helpers |
| `application.yml` | Exposes all endpoints, enables K8s probes, sets `show-details: always` |

---

## 5. Run the Demo

```bash
cd spring-boot/actuator-demo
mvn spring-boot:run
```

### Explore the actuator index

```bash
curl http://localhost:8080/actuator | jq .
# → lists every exposed endpoint with its href
```

### Health — normal state

```bash
curl http://localhost:8080/actuator/health | jq .
# → status: UP, components: diskSpace UP, paymentService UP, ping UP
```

### Health — simulate a downstream outage

```bash
curl -X POST http://localhost:8080/order/payment/break
# → "Payment service is now DOWN"

curl http://localhost:8080/actuator/health | jq .
# → status: DOWN, paymentService: { status: DOWN, error: "connection refused" }

curl -X POST http://localhost:8080/order/payment/fix
curl http://localhost:8080/actuator/health | jq .
# → status: UP again
```

### Info — static + dynamic data

```bash
curl http://localhost:8080/actuator/info | jq .
# → app.name, app.version (from application.yml)
# → runtime.javaVersion, runtime.processors (from AppInfoContributor)
# → team.name, team.email
```

### Env — see all property sources

```bash
curl http://localhost:8080/actuator/env | jq .
# → propertySources: systemProperties, systemEnvironment, application.yml, ...
# → each with its key-value pairs

# Look up one specific key
curl http://localhost:8080/actuator/env/spring.application.name | jq .
```

### Loggers — change level at runtime (no restart!)

```bash
# 1. See current level
curl http://localhost:8080/actuator/loggers/com.example | jq .
# → { "configuredLevel": "INFO", "effectiveLevel": "INFO" }

# 2. Hit GET /order/1 and notice log output is INFO level
curl http://localhost:8080/order/ORD-001

# 3. Turn on DEBUG at runtime
curl -X POST http://localhost:8080/actuator/loggers/com.example \
     -H "Content-Type: application/json" \
     -d '{"configuredLevel": "DEBUG"}'

# 4. Hit the endpoint again — now you see DEBUG logs too
curl http://localhost:8080/order/ORD-001

# 5. Revert
curl -X POST http://localhost:8080/actuator/loggers/com.example \
     -H "Content-Type: application/json" \
     -d '{"configuredLevel": "INFO"}'
```

### Metrics — drill-down

```bash
# List all metric names
curl http://localhost:8080/actuator/metrics | jq '.names'

# JVM heap memory
curl http://localhost:8080/actuator/metrics/jvm.memory.used | jq .

# HTTP request stats
curl http://localhost:8080/actuator/metrics/http.server.requests | jq .

# Filter by HTTP status
curl "http://localhost:8080/actuator/metrics/http.server.requests?tag=status:200" | jq .
```

### Mappings — see all HTTP endpoints

```bash
curl http://localhost:8080/actuator/mappings | jq .
# → shows every @RequestMapping in the app including the actuator endpoints themselves
```

### K8s probes

```bash
curl http://localhost:8080/actuator/health/liveness  | jq .
# → { "status": "UP" }

curl http://localhost:8080/actuator/health/readiness | jq .
# → { "status": "UP" }
```

---

## 6. Interview Angles

**Q: What is Spring Boot Actuator and why do you use it?**

Actuator adds production-ready management endpoints to a Spring Boot app — health checks, metrics, config inspection, thread dumps, live log-level changes — without writing custom code. In production it answers operational questions (is the service healthy? what property value is it actually using? why is memory growing?) without requiring a redeploy or restart.

---

**Q: By default, which endpoints are exposed? How do you control exposure?**

Only `/health` and `/info` are exposed over HTTP by default — a secure-by-default decision. You control exposure via:
```yaml
management.endpoints.web.exposure.include: health, info, metrics, loggers
management.endpoints.web.exposure.exclude: shutdown
```
There are two separate concepts: **enabled** (does the endpoint exist in the app context?) and **exposed** (is it reachable over HTTP?). Most endpoints are enabled but not exposed. `shutdown` is the exception — disabled by default; you must explicitly enable it.

---

**Q: How do you write a custom health indicator?**

Implement `HealthIndicator` and annotate with `@Component`. Spring names it by stripping `HealthIndicator` from the class name. Return `Health.up()` or `Health.down()` with optional `.withDetail()` key-value pairs. The overall `/actuator/health` status is the worst of all components — one DOWN component makes the whole app DOWN.

---

**Q: What is the difference between liveness and readiness probes?**

| | Liveness | Readiness |
|---|---|---|
| Question | "Is the process alive?" | "Can it serve traffic?" |
| If DOWN | K8s kills and restarts the pod | K8s removes pod from load balancer |
| Use case | Detect deadlocks, fatal errors | Startup delay, downstream dependency down |

You should NOT add business dependency checks (DB, payment service) to liveness — if your DB goes down you don't want K8s restarting all your pods. Put them in readiness so traffic is rerouted while the dependency recovers.

---

**Q: How do you change a log level in production without restarting?**

```bash
POST /actuator/loggers/com.example.orderservice
Content-Type: application/json
{ "configuredLevel": "DEBUG" }
```

This takes effect immediately. Revert it by setting it back to `INFO` or `null` (inherits from parent logger). Useful for investigating a production issue — turn on DEBUG for 5 minutes, collect logs, turn it off.

---

**Q: How do you secure Actuator endpoints in production?**

Three approaches:
1. **Expose only safe endpoints** — `include: health, info` in config, never expose `env`, `beans`, `shutdown` over the public network.
2. **Different port** — `management.server.port: 9090` so management endpoints are on a separate port not accessible externally.
3. **Spring Security** — add `spring-boot-starter-security` and configure `SecurityFilterChain` to require `ACTUATOR_ADMIN` role for `/actuator/**`.

---

**Q: What does /actuator/conditions show?**

The auto-configuration report — which `@Conditional` classes matched (and why) and which didn't. Invaluable when debugging "why didn't auto-configuration X kick in?" — it tells you exactly which condition failed and what value it evaluated.
