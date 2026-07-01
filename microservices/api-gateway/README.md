# API Gateway — Routing, Load Balancing, GlobalFilter (JWT), GatewayFilter (Retry + Circuit Breaker)

---

## 1. What

An **API Gateway** is the single entry point for all client traffic in a microservices system. Clients talk only to the gateway — the gateway routes each request to the correct downstream service, applies cross-cutting logic, and shields clients from the internal topology.

**Spring Cloud Gateway** (WebFlux / reactive) is the Spring Cloud standard. It composes requests through a **filter chain**:

```
Client Request
  → GlobalFilters (every route: logging, JWT auth, ...)
    → GatewayFilters (per-route: StripPrefix, CircuitBreaker, Retry, ...)
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
- **GlobalFilter — JwtAuthFilter**: validates Bearer JWT on every route; whitelists `/token`, `/fallback/`, `/actuator/`
- **GatewayFilter — StripPrefix**: strips the gateway prefix (e.g., `/api`) before forwarding
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
Is path whitelisted? (/token, /fallback/, /actuator/)
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
        └── StripPrefix  ← built-in GatewayFilter
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
      basedOnPreviousValue: false
```

**Why only GET?** POST/PUT are not safely retryable — retrying a payment or order creation would duplicate it. Only retry idempotent methods.

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
  - StripPrefix=1
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
| `api-gateway/src/main/java/com/example/gateway/JwtAuthFilter.java` | `GlobalFilter` (order 0) — validates JWT, adds `X-Authenticated-User` header |
| `api-gateway/src/main/java/com/example/gateway/LoggingFilter.java` | `GlobalFilter` (order -1) — wraps everything, logs all requests in/out |
| `api-gateway/src/main/java/com/example/gateway/TokenController.java` | `GET /token?user=xxx` — issues demo JWTs (whitelisted from JWT check) |
| `api-gateway/src/main/java/com/example/gateway/FallbackController.java` | `GET /fallback/inventory` + `/fallback/orders` — CB fallback responses |
| `api-gateway/src/main/java/com/example/gateway/ApiGatewayApp.java` | Gateway Entry point |
| `api-gateway/src/main/resources/application.yml` | Routes: StripPrefix + CircuitBreaker + Retry; Resilience4j config; JWT secret |
| `inventory-service/src/main/java/com/example/inventoryservice/InventoryController.java` | Adds `POST /inventory/break` + `POST /inventory/fix` |
| `order-service/src/main/java/com/example/orderservice/OrderController.java` | Simple downstream endpoint for testing `/api/orders/**` |

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

**Step 4: Call order-service with token**

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/orders/42
# Order #42 confirmed — routed via API Gateway to order-service (port 9082)
```

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

---

### Demo C — Circuit Breaker

**Step 1: Still broken — accumulate failures**

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
```

**Step 2: Circuit opens — fallback returned instantly**

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
# HTTP 503 — Inventory service is temporarily unavailable (circuit breaker is OPEN)...
```

**Step 3: Check CB state via Actuator**

```bash
curl http://localhost:8080/actuator/circuitbreakers | jq .
# → { "circuitBreakers": { "inventory-cb": { "state": "OPEN", ... } } }
```

**Step 4: Wait 10s → HALF_OPEN, fix inventory, watch CB close**

```bash
curl -X POST http://localhost:9081/inventory/fix
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/inventory/item-1
```

---

## 6. Interview Angles

**Q: GlobalFilter vs GatewayFilter — which do you use for JWT and why?**

`GlobalFilter` for JWT. JWT is a cross-cutting concern — every route needs it. `GatewayFilter` is used for route-specific tasks, like `StripPrefix`.

**Q: Why does CircuitBreaker go before Retry in the filter list?**

Filters listed earlier wrap filters listed later. `CB -> Retry -> downstream` ensures retries happen inside a single CB call unit.

**Q: Why does the Retry filter only retry GET requests?**

Idempotency. POST/PUT might duplicate transactions.
