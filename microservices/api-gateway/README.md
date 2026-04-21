# API Gateway — Routing, Load Balancing, GlobalFilter (JWT), GatewayFilter (Retry + Circuit Breaker)

---

## 1. What

An **API Gateway** is the single entry point for all client traffic in a microservices system. Clients talk only to the gateway — the gateway routes each request to the correct downstream service, applies cross-cutting logic, and shields clients from the internal topology.

**Spring Cloud Gateway** (WebFlux / reactive) is the Spring Cloud standard. It composes requests through a **filter chain**:

```
Client Request
  → GlobalFilters (every route: logging, JWT auth, ...)
    → GatewayFilters (per-route: CircuitBreaker, Retry, StripPrefix, ...)
      → downstream service
        → response flows back through all filters
```

Two types of filters:

| Filter type | Applies to | Configured in |
|-------------|-----------|---------------|
| **GlobalFilter** | ALL routes automatically | Java `@Component` |
| **GatewayFilter** | ONE specific route | `filters:` list in `application.yml` |

This demo implements:
- **GlobalFilter — LoggingFilter**: logs method, path, status, latency for every request
- **GlobalFilter — JwtAuthFilter**: validates Bearer JWT on every route; whitelists `/token`, `/fallback/**`, `/actuator/**`
- **GatewayFilter — Retry**: retries failed calls with exponential backoff before the CB sees the outcome
- **GatewayFilter — CircuitBreaker**: trips after sustained failures; redirects to a local fallback endpoint

---

## 2. Why

### Without an API Gateway
```
Client → http://inventory:9081/inventory/item-1   ← hardcoded, breaks when scaled
Client → http://order:9082/orders/42              ← each service handles its own auth
```
- Every service must implement JWT validation independently
- Every service must implement retry and circuit breaking
- Clients must track every service's host:port

### With the Gateway
```
Client → http://gateway:8080/api/inventory/item-1
                         ↓
            JwtAuthFilter validates token
                         ↓
            CircuitBreaker: is inventory CB OPEN?
              YES → return fallback immediately (no network hop)
              NO  → Retry → try downstream up to 3 times
                         ↓
            LoadBalancer resolves lb://inventory-service via Eureka
            Round Robin picks: 127.0.0.1:9081 or 127.0.0.1:9083
                         ↓
            Downstream receives: GET /inventory/item-1
                                  X-Authenticated-User: rohit
```

Auth, resilience, and load balancing live in one place. Downstream services are stateless and dumb — they receive requests with the user already identified in a header.

---

## 3. How Each Feature Works

### 3.1 GlobalFilter — JWT Authentication

```
Every request → JwtAuthFilter (order 0, runs inside LoggingFilter wrapper)
  ↓
Is path whitelisted? (/token, /fallback/**, /actuator/**)
  YES → skip JWT, continue chain
  NO  ↓
Read Authorization: Bearer <token> header
  Missing → 401 immediately (chain never proceeds)
  Present ↓
Parse + validate JWT with jjwt (HS256, secret from application.yml)
  Expired / bad signature → 401 immediately
  Valid ↓
Extract subject ("rohit") from claims
Add X-Authenticated-User: rohit to forwarded request headers
Continue filter chain → request reaches GatewayFilters and then downstream
```

**Filter ordering:**

```
LoggingFilter  (order -1)  ← outermost — wraps everything, logs all requests
  └── JwtAuthFilter (order  0)  ← auth check; 401 flows back through LoggingFilter
        └── CircuitBreakerFilter  ← built-in GatewayFilter
              └── RetryFilter
                    └── downstream
```

Lower order number = higher priority = runs first. LoggingFilter at -1 wraps JwtAuthFilter at 0, so even rejected-401 requests appear in the gateway log with status 401.

---

### 3.2 GatewayFilter — Retry

The **Retry** filter re-sends a failed request to the downstream without the client knowing:

```
Client sends one request
     ↓
Retry filter intercepts
     ↓
Attempt 1 → downstream returns 500 → wait 100ms
     ↓
Attempt 2 → downstream returns 500 → wait 200ms (factor=2)
     ↓
Attempt 3 → downstream returns 500 → wait 400ms
     ↓
All retries exhausted → propagate failure upward to CircuitBreaker
```

Configuration:
```yaml
- name: Retry
  args:
    retries: 3                                              # max 3 attempts after the original
    statuses: INTERNAL_SERVER_ERROR,BAD_GATEWAY,SERVICE_UNAVAILABLE  # retry on these HTTP status codes
    methods: GET                                            # only retry idempotent methods
    backoff:
      firstBackoff: 100ms
      maxBackoff: 1000ms
      factor: 2                                             # doubles the wait each time
```

**Why only GET?** POST/PUT are not safely retryable — retrying a payment or order creation would duplicate it. Only retry idempotent methods.

**Evidence in logs:** Breaking the inventory service and sending one gateway request produces 3 or 4 log lines in the inventory-service (one per retry attempt), but only one log line in the client.

---

### 3.3 GatewayFilter — CircuitBreaker

The CB wraps the (Retry + downstream) call. It tracks outcomes in a sliding window:

```
CLOSED (normal):
  Call outcome (after retries) → success or failure → recorded in window
  failure rate >= 60% in last 5 calls → OPEN

OPEN (protective):
  No call reaches downstream at all
  Instead: immediately forward to fallbackUri
  After wait-duration-in-open-state (10s) → HALF_OPEN

HALF_OPEN (probing):
  2 probe calls allowed through
  Both succeed → CLOSED
  Any fail    → OPEN (wait-duration resets)
```

The CB + Retry filter order in the yaml matters:

```yaml
filters:
  - name: CircuitBreaker   # OUTER — wraps Retry
    args:
      name: inventory-cb
      fallbackUri: forward:/fallback/inventory
  - name: Retry            # INNER — fires inside a single CB call
```

This way: retries happen inside one CB "call unit". CB only counts the net outcome after all retries.

---

## 4. Code

| File | What it is |
|------|-----------|
| `api-gateway/JwtAuthFilter.java` | `GlobalFilter` (order 0) — validates JWT, adds `X-Authenticated-User` header |
| `api-gateway/LoggingFilter.java` | `GlobalFilter` (order -1) — wraps everything, logs all requests in/out |
| `api-gateway/TokenController.java` | `GET /token?user=xxx` — issues demo JWTs (whitelisted from JWT check) |
| `api-gateway/FallbackController.java` | `GET /fallback/inventory` + `/fallback/orders` — CB fallback responses |
| `api-gateway/ApiGatewayApp.java` | Entry point |
| `api-gateway/application.yml` | Routes: StripPrefix + CircuitBreaker + Retry; Resilience4j config; JWT secret |
| `inventory-service/InventoryController.java` | Adds `POST /inventory/break` + `POST /inventory/fix`; logs `X-Authenticated-User` |

---

## 5. Run the Demo

### Step 1 — Build

```bash
cd microservices/api-gateway
mvn clean package -DskipTests
```

### Step 2 — Start services (4 terminals, start Eureka first)

```bash
# Terminal 1
cd eureka-server && mvn spring-boot:run

# Terminal 2
cd inventory-service && mvn spring-boot:run

# Terminal 3
cd order-service && mvn spring-boot:run

# Terminal 4
cd api-gateway && mvn spring-boot:run
```

---

### Demo A — JWT Authentication

**Step 1: Call without token → 401**

```bash
curl -i http://localhost:8080/api/inventory/item-1
# HTTP/1.1 401 Unauthorized
# WWW-Authenticate: Bearer realm="api-gateway", error="Missing Authorization: Bearer <token> header"
```

**Step 2: Get a token**

```bash
TOKEN=$(curl -s "http://localhost:8080/token?user=rohit")
echo $TOKEN
# eyJhbGciOiJIUzI1NiJ9...
```

**Step 3: Call with token → 200**

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
# Product 'item-1' is IN STOCK  [served by instance on port 9081]
```

**Step 4: Observe identity propagation in inventory-service logs**

```
[port=9081] [user=rohit] /inventory/item-1 → HEALTHY — returning stock
```

The gateway extracted `rohit` from the JWT and forwarded it as `X-Authenticated-User: rohit`. The downstream service received the caller's identity without doing any JWT parsing itself.

---

### Demo B — Retry Filter

**Step 1: Break the inventory service**

```bash
curl -X POST http://localhost:9081/inventory/break
# Inventory service is now BROKEN — GET /inventory/** returns HTTP 500
```

**Step 2: Send one request through the gateway**

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
```

**Step 3: Check inventory-service logs — you see 3 attempts from 1 client call**

```
[port=9081] /inventory/item-1 → BROKEN — returning 500
[port=9081] /inventory/item-1 → BROKEN — returning 500
[port=9081] /inventory/item-1 → BROKEN — returning 500
```

3 log lines from 1 client request = Retry firing 3 times with 100ms/200ms/400ms backoff.

---

### Demo C — Circuit Breaker

**Step 1: Still broken — accumulate failures**

```bash
# Each call → 3 retries → 1 CB failure event
# Need 3 failures in the 5-call sliding window (60% threshold)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
```

**Step 2: Circuit opens — fallback returned instantly**

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
# HTTP 503 — Inventory service is temporarily unavailable (circuit breaker is OPEN)...

# No log line in inventory-service — the CB stopped the call before it was made
```

**Step 3: Check CB state via Actuator**

```bash
curl http://localhost:8080/actuator/circuitbreakers | jq .
# → { "circuitBreakers": { "inventory-cb": { "state": "OPEN", ... } } }

curl http://localhost:8080/actuator/health | jq .
# → { "status": "DEGRADED", "components": { "circuitBreakers": { "inventory-cb": "OPEN" } } }
```

**Step 4: Wait 10s → HALF_OPEN, fix inventory, watch CB close**

```bash
# Fix the service
curl -X POST http://localhost:9081/inventory/fix

# After 10s, send 2 probe calls (permitted-calls-in-half-open = 2)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
# Both succeed → CB transitions HALF_OPEN → CLOSED

curl http://localhost:8080/actuator/circuitbreakers | jq .
# → { "inventory-cb": { "state": "CLOSED", ... } }
```

---

### Demo D — Load Balancing (recap with auth)

```bash
# Start a second inventory-service instance
cd inventory-service
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9083

# Hit gateway repeatedly — see port alternating in response
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
# ... [served by instance on port 9081]
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
# ... [served by instance on port 9083]
```

---

## 6. Interview Angles

**Q: GlobalFilter vs GatewayFilter — which do you use for JWT and why?**

`GlobalFilter` for JWT. JWT is a cross-cutting concern — every route needs it. If you used a `GatewayFilter`, you'd add it to every route's filter list and forget it on new routes. `GlobalFilter` is automatic for all routes; exceptions (like `/token`) are handled by a whitelist inside the filter logic. Contrast with `StripPrefix` — that's route-specific (you only strip the `/api` prefix on certain routes), so it belongs as a `GatewayFilter` in the route config.

---

**Q: Why does CircuitBreaker go before Retry in the filter list?**

Because GatewayFilters listed earlier wrap filters listed later. Putting CB first means:
```
CB (outer) → Retry (inner) → downstream
```
Retry fires multiple attempts inside a single CB call. The CB only counts the net outcome after all retries fail. If you reversed the order (Retry → CB), each individual retry attempt would count as a separate CB failure — the CB would trip after far fewer "real" failures.

---

**Q: What does JwtAuthFilter add as a header, and why?**

`X-Authenticated-User: <subject>`. This is the **token relay / identity propagation** pattern. Benefits:
1. Downstream services don't need the JWT library — they just read a header
2. Downstream services are decoupled from the auth mechanism (could switch from JWT to opaque tokens without touching downstream code)
3. Centralized auth: the gateway is the single point of JWT validation; downstream services trust gateway-set headers (network policy ensures only the gateway can reach them)

---

**Q: What happens to retries when the Circuit Breaker is OPEN?**

Retries never fire. When the CB is OPEN, the `CircuitBreakerGatewayFilter` short-circuits immediately and redirects to the fallback URI. The Retry filter, which is inside the CB's wrapped chain, is never invoked. This is the whole point — the CB prevents hammering a known-failing service with retries.

---

**Q: Why does the Retry filter only retry GET requests?**

Idempotency. A GET for stock data can be retried safely — you always want the same result. A POST to create an order must NOT be retried automatically — each retry creates a duplicate order. Retry is only safe for idempotent operations (GET, HEAD, PUT, DELETE by convention). The gateway config explicitly lists `methods: GET` to enforce this.

---

**Q: What does `forward:/fallback/inventory` mean in the CircuitBreaker config?**

`forward:` is an internal server-side forward — the gateway handles the request locally using its own `@RestController` (`FallbackController`). No HTTP round-trip happens. The client sees the fallback response (503) but it came from the gateway itself, not from an external service. This is faster than redirecting to an external fallback service and avoids adding another network hop in the degraded path.

---

**Q: How would you make JWT validation stateless vs stateful?**

| | Stateless (this demo) | Stateful (opaque tokens) |
|---|---|---|
| Token format | JWT — self-contained | Opaque string — lookup required |
| Validation | Verify signature locally (no DB) | HTTP call to auth server per request |
| Revocation | Impossible until expiry (short TTL needed) | Immediate (delete from DB) |
| Scaling | Excellent — no shared state | Bottleneck on auth server |

JWTs are stateless — you can validate without contacting any server. The trade-off is revocation: once issued, a JWT cannot be invalidated before its expiry. Mitigation: keep expiry short (15 min) and use refresh tokens for session continuity.

---

**Q: Spring Cloud Gateway vs Zuul — key differences?**

| | Spring Cloud Gateway | Zuul 1 |
|---|---|---|
| I/O model | Reactive (WebFlux + Netty) | Blocking (Servlet + Tomcat) |
| Concurrency | Event loop — few threads handle thousands of connections | One thread per connection |
| Backpressure | Native (Project Reactor) | None |
| Spring Boot 3 support | Full | Deprecated |

Zuul 2 added non-blocking I/O but Spring Cloud dropped Zuul in favour of Gateway. Always use Spring Cloud Gateway in new Spring Cloud projects.
