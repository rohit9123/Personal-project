# Circuit Breaker

---

## 1. What

The **Circuit Breaker pattern** monitors calls to a downstream service and, when failures exceed a threshold, **opens** the circuit — subsequent calls are immediately rejected (fast-fail) without hitting the network. After a wait period, the circuit moves to **half-open** to probe recovery, then either closes (recovered) or reopens (still failing).

Three states:

| State | Behaviour |
|---|---|
| **CLOSED** | Normal operation — calls pass through, outcomes tracked in sliding window |
| **OPEN** | Fast-fail — no calls reach the downstream, fallback is returned immediately |
| **HALF_OPEN** | Probe mode — limited calls allowed through to test if downstream has recovered |

---

## 2. Why

Network calls to downstream services can fail. In distributed systems, a slow or failing dependency will eventually cascade — every caller blocks, threads fill up, and the calling service itself becomes unavailable.

**Without Circuit Breaker:**
```
Order Service → inventory is DOWN → waits for timeout (30s) → returns error
500 concurrent users each hold a thread for 30s → thread pool exhausted
New requests queue up → Order Service starts timing out too → cascade failure
```

**With Circuit Breaker:**
```
Order Service → inventory DOWN → first 3 failures trip the circuit
Subsequent calls → immediate fast-fail (no network hop) → fallback returned in <1ms
Thread pool stays free → Order Service remains responsive for other work
After 10s → probe calls test recovery → circuit closes → normal operation resumes
```

**Why not just use Retry?**

Retry helps with *transient* blips (a single request fails, retry it). Circuit Breaker handles *sustained* failures (the dependency is down for 30 seconds). They are **complementary**: retry for occasional glitches, circuit breaker to stop retrying into a wall. Combined: retry a few times per call, but if the circuit is open, don't even try.

---

## 3. How It Works

### State machine

```
          failures >= threshold
CLOSED  ─────────────────────────► OPEN
  ▲                                  │
  │    all probe calls succeed        │  wait-duration expires
  │                                  ▼
CLOSED ◄────────────────────── HALF_OPEN
         any probe call fails    │
                                 ▼
                               OPEN  (resets the wait timer)
```

### Sliding window (COUNT_BASED)

```
Call 1: SUCCESS  [S _ _ _ _]  failure rate = 0%   (window not full, no eval)
Call 2: SUCCESS  [S S _ _ _]  failure rate = 0%   (window not full, no eval)
Call 3: FAILURE  [S S F _ _]  failure rate = 0%   (window not full, no eval)
Call 4: FAILURE  [S S F F _]  failure rate = 0%   (window not full, no eval)
Call 5: FAILURE  [S S F F F]  failure rate = 60%  ← threshold hit → OPEN ✗
```

### What happens when the circuit is OPEN

```
@CircuitBreaker annotation
   ↓ AOP proxy intercepts the method call
   ↓ checks CircuitBreaker state
   ↓ state = OPEN → throws CallNotPermittedException immediately
   ↓ Spring AOP catches this → calls fallbackMethod
   ↓ caller gets the fallback response in microseconds (no network hop)
```

### HALF_OPEN recovery

After `wait-duration-in-open-state` (10s in this demo), the CB automatically moves to HALF_OPEN and allows exactly `permitted-number-of-calls-in-half-open-state` (2) probe calls through:
- Both succeed → CLOSED (normal operation resumes)
- Any one fails → OPEN (wait-duration resets, try again later)

---

## 4. Code

| File | What it is |
|------|-----------|
| `order-service/InventoryClient.java` | `@CircuitBreaker(name="inventory-cb")` wraps the HTTP call; fallback returns cached response |
| `order-service/CircuitBreakerEventLogger.java` | Subscribes to CB events — logs every state transition and rejected call |
| `order-service/OrderController.java` | `GET /order/{id}` (place order with CB) and `GET /order/circuit-state` (inspect metrics) |
| `order-service/application.yml` | Full CB config: window size, threshold, wait duration, half-open probes |
| `inventory-service/InventoryController.java` | `GET /inventory/{id}`, `POST /inventory/break`, `POST /inventory/fix` |

---

## 5. Run the Demo

### Start both services

```bash
# Terminal 1 — inventory-service (port 8082)
cd inventory-service && mvn spring-boot:run

# Terminal 2 — order-service (port 8080)
cd order-service && mvn spring-boot:run
```

### Step-by-step walkthrough

**Step 1 — Normal operation (CLOSED)**

```bash
# All calls succeed, CB stays CLOSED
curl http://localhost:8080/order/item-1
curl http://localhost:8080/order/item-2
curl http://localhost:8080/order/circuit-state
# → { "state": "CLOSED", "failureRate": "-1.0%", "bufferedCalls": 2, ... }
```

**Step 2 — Break the inventory service**

```bash
curl -X POST http://localhost:8082/inventory/break
# → "Inventory service is now BROKEN (returning 500 for all requests)"
```

**Step 3 — Accumulate failures to trip the circuit**

```bash
# Each of these returns the FALLBACK response after the real call fails
# Watch order-service logs for: >>> CB call FAILED
curl http://localhost:8080/order/item-1   # failure 1 of 5
curl http://localhost:8080/order/item-2   # failure 2 of 5
curl http://localhost:8080/order/item-3   # failure 3 of 5  ← 60% threshold hit
# Logs: >>> CB STATE CHANGE: CLOSED → OPEN
curl http://localhost:8080/order/item-4   # fast-fail: CB REJECTED (no HTTP hop)
curl http://localhost:8080/order/item-5   # fast-fail: CB REJECTED (no HTTP hop)
```

```bash
curl http://localhost:8080/order/circuit-state
# → { "state": "OPEN", "failureRate": "60.0%", "notPermittedCalls": 2, ... }
```

**Step 4 — Wait 10 seconds for HALF_OPEN**

```bash
# After 10s the CB transitions automatically:
# Logs: >>> CB STATE CHANGE: OPEN → HALF_OPEN

curl http://localhost:8080/order/circuit-state
# → { "state": "HALF_OPEN", ... }
```

**Step 5 — Fix inventory and let probe calls succeed**

```bash
curl -X POST http://localhost:8082/inventory/fix
# → "Inventory service is now HEALTHY"

# Probe call 1 (allowed through in HALF_OPEN)
curl http://localhost:8080/order/item-1
# Probe call 2 (allowed through in HALF_OPEN)
curl http://localhost:8080/order/item-2
# Logs: >>> CB STATE CHANGE: HALF_OPEN → CLOSED
```

**Step 6 — Normal operation restored**

```bash
curl http://localhost:8080/order/circuit-state
# → { "state": "CLOSED", "failureRate": "0.0%", ... }
```

### Bonus — reopen from HALF_OPEN

```bash
# Break inventory AGAIN before fixing it
curl -X POST http://localhost:8082/inventory/break
# Wait 10s for HALF_OPEN, then let the probe calls fail
curl http://localhost:8080/order/item-1  # probe call fails → back to OPEN
# Logs: >>> CB STATE CHANGE: HALF_OPEN → OPEN
```

### Actuator endpoints

```bash
# All circuit breaker instances and their current state
curl http://localhost:8080/actuator/circuitbreakers

# Recent CB events (last 100 by default)
curl http://localhost:8080/actuator/circuitbreakerevents

# Health check — DEGRADED when CB is OPEN
curl http://localhost:8080/actuator/health
```

### Expected log output (order-service)

```
=== ORDER request: product=item-1, CB state=CLOSED ===
>>> CB call FAILED — HttpServerErrorException (failure rate may be rising)

=== ORDER request: product=item-3, CB state=CLOSED ===
>>> CB call FAILED — HttpServerErrorException (failure rate may be rising)
>>> CB STATE CHANGE: CLOSED → OPEN

=== ORDER request: product=item-4, CB state=OPEN ===
>>> CB REJECTED call (circuit is OPEN) — fast-failing to fallback

>>> CB STATE CHANGE: OPEN → HALF_OPEN

=== ORDER request: product=item-1, CB state=HALF_OPEN ===
>>> CB call SUCCESS (duration: 4ms)
>>> CB STATE CHANGE: HALF_OPEN → CLOSED
```

---

## 6. Interview Angles

**Q: What problem does the Circuit Breaker solve?**

It prevents **cascading failures**. When a downstream dependency is slow or down, every call to it blocks a thread. Without a circuit breaker, the caller's thread pool fills up and it too becomes unresponsive — the failure cascades up the call chain. The circuit breaker detects the sustained failure and starts fast-failing (microseconds, no thread blocked) instead of waiting for timeouts (seconds, thread blocked the whole time).

---

**Q: Explain the three states.**

- **CLOSED**: Normal. Calls pass through. The CB tracks outcomes in a sliding window. When the failure rate exceeds the threshold, it opens.
- **OPEN**: Protective. All calls are immediately rejected — no network hop. The fallback runs instead. After `wait-duration`, it transitions to HALF_OPEN.
- **HALF_OPEN**: Probing. A limited number of calls are allowed through to test recovery. If they all succeed, the circuit closes. If any fails, it reopens and the wait-duration resets.

---

**Q: Circuit Breaker vs Retry — when do you use each?**

| | Retry | Circuit Breaker |
|---|---|---|
| Failure type | Transient (single call fails) | Sustained (dependency is down) |
| Behaviour | Wait, try again | Stop trying entirely |
| State | Stateless per-call | Stateful across many calls |
| Scope | One call | All calls to that dependency |

Use them together: retry 2–3 times for a single call; circuit breaker prevents you from retrying into a wall when the dependency has been failing for 30 seconds. A common stack is `@CircuitBreaker` wrapping `@Retry` — retries happen while the circuit is CLOSED, and once the circuit opens, retries stop.

---

**Q: What is the sliding window and why does `minimum-number-of-calls` matter?**

The sliding window records the last N outcomes (COUNT_BASED) and computes the failure rate. `minimum-number-of-calls` ensures the CB doesn't trip after 1 failure out of 1 call (100%) before the window is full. With `minimum=5` and `threshold=60%`: the CB will not evaluate failure rate until at least 5 calls have been made, preventing premature tripping on startup noise.

---

**Q: What is the fallback and what should it return?**

The fallback is the method called when the primary call fails (any exception) or the circuit is open. It should return a **useful degraded response**: cached data from a previous successful call, a default safe value, or an indicator that the feature is temporarily unavailable. The fallback should never call the same failing downstream — that defeats the purpose. It should be fast and free of I/O.

---

**Q: How do you decide the threshold values?**

- `sliding-window-size`: large enough to filter noise, small enough to react quickly. 10–20 for production services with moderate traffic.
- `failure-rate-threshold`: 50–60% is typical. Too low → nuisance trips on small noise. Too high → too slow to protect.
- `wait-duration-in-open-state`: how long your downstream typically takes to recover. 30–60s for most service restarts.
- `permitted-calls-in-half-open`: 3–5. Too few → risky single point of evidence. Too many → too much traffic during recovery.

---

**Q: How does Circuit Breaker relate to bulkhead?**

Bulkhead limits **concurrency** (how many calls can run simultaneously — prevents thread pool saturation). Circuit Breaker limits **failure propagation** (stops calls when failure rate is high). They're complementary: bulkhead prevents thread pool exhaustion under load; circuit breaker prevents continuous hammering of a failing dependency. In production systems you often use both.
