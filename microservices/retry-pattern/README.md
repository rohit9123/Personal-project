# Retry Pattern

---

## 1. What

The **Retry pattern** automatically re-executes a failed operation after a delay, transparently recovering from transient failures without exposing the error to the caller.

Three strategies are demonstrated:

| Strategy | Wait between retries |
|---|---|
| **Fixed** | Constant delay (e.g. 500ms every time) |
| **Exponential backoff** | Delay doubles after each failure (1s → 2s → 4s) |
| **Exponential + Jitter** | Exponential delay ± random factor (1s±50% → 2s±50% → 4s±50%) |

---

## 2. Why

Network calls fail. In distributed systems, transient failures are **normal** — packet loss, brief GC pauses, momentary overload, rolling restarts. A single failure doesn't mean the downstream is permanently broken.

**Without retry:**
```
Order Service → HTTP 500 from Inventory → returns error to user
User retries manually → inventory was fine after 300ms — wasted failure
```

**With retry:**
```
Order Service → HTTP 500 → waits 500ms → retries → HTTP 200 ✓
User sees success — the transient failure was invisible
```

**Why not just always use fixed retry?**

Fixed retry creates **thundering herd** under load: if 500 clients all got the same error at time T, they all retry at T+500ms simultaneously, spiking load on the recovering service and potentially pushing it back into failure. Exponential backoff reduces the retry rate over time; jitter spreads concurrent retries across a window.

---

## 3. How It Works

### Fixed retry

```
Attempt 1 → FAIL → [500ms] → Attempt 2 → FAIL → [500ms] → Attempt 3 → SUCCESS
```

Every wait is identical. Simple and predictable. Use when:
- Downstream recovers in a known short window
- You have low concurrency (thundering herd is not a concern)

### Exponential backoff

```
Attempt 1 → FAIL → [1s] → Attempt 2 → FAIL → [2s] → Attempt 3 → FAIL → [4s] → Attempt 4 → SUCCESS
```

Wait = `base × multiplier^(attempt-1)`. Each failure gives the dependency more time to recover. Use when:
- Downstream failure is due to overload that needs time to drain
- Retry count is low and concurrency is moderate

### Exponential + Jitter

```
Attempt 1 → FAIL → [~0.8s] → Attempt 2 → FAIL → [~2.4s] → Attempt 3 → SUCCESS
```

`randomized-wait-factor=0.5` → actual wait is uniformly random in `[base×0.5, base×1.5]`.

500 concurrent callers that all failed at time T will now retry in a spread window instead of a synchronized spike. **This is the production-recommended approach for high-concurrency services.**

### What Resilience4j does internally

```
@Retry annotation
   ↓ AOP proxy intercepts the method call
   ↓ calls the method
   ↓ if exception matches retry-exceptions → schedule next attempt after computed wait
   ↓ repeats until success or maxAttempts exhausted
   ↓ if exhausted → calls fallbackMethod (if configured) or re-throws
```

The retry happens **on the same thread** (synchronous) — the caller blocks for the total retry duration.

---

## 4. Code

| File | What it is |
|------|-----------|
| `order-service/OrderController.java` | Three endpoints — one per retry strategy |
| `order-service/FixedRetryClient.java` | `@Retry(name="fixed-retry")` — constant 500ms wait |
| `order-service/ExponentialRetryClient.java` | `@Retry(name="exponential-retry")` — doubling wait |
| `order-service/JitterRetryClient.java` | `@Retry(name="jitter-retry")` — exponential + random ±50% |
| `order-service/RetryEventLogger.java` | Subscribes to retry events — logs each attempt with attempt# |
| `order-service/application.yml` | All three Resilience4j retry configs |
| `inventory-service/InventoryController.java` | Flaky endpoint — fails ?failTimes=N times then succeeds |

### Run order

```bash
# Terminal 1 — inventory-service (port 8082)
cd inventory-service && mvn spring-boot:run

# Terminal 2 — order-service (port 8080)
cd order-service && mvn spring-boot:run
```

### Demo — all three strategies

```bash
# Fixed retry — inventory fails 2 times, 3rd attempt succeeds
# Watch order-service logs: see attempt 1, 2 fail; attempt 3 succeed
curl http://localhost:8080/order/item-1/fixed

# Exponential backoff — same scenario but with increasing waits (1s, 2s)
curl http://localhost:8080/order/item-2/exponential

# Jitter — same scenario but waits are randomised (notice different timings each run)
curl http://localhost:8080/order/item-3/jitter

# Trigger fallback — failTimes=10 exceeds maxAttempts=4 → all retries exhausted
curl "http://localhost:8080/order/item-4/fixed?failTimes=10"
curl "http://localhost:8080/order/item-5/exponential?failTimes=10"
curl "http://localhost:8080/order/item-6/jitter?failTimes=10"
```

### Actuator endpoints

```bash
# View retry metrics and attempt counts
curl http://localhost:8080/actuator/retries
curl http://localhost:8080/actuator/health
```

### Expected log output (order-service)

```
=== FIXED RETRY demo: product=item-1, failTimes=2 ===
[fixed-retry] calling inventory-service for product item-1 (failTimes=2)
[fixed-retry] attempt #1 FAILED — retrying due to: HttpServerErrorException
[fixed-retry] calling inventory-service for product item-1 (failTimes=2)
[fixed-retry] attempt #2 FAILED — retrying due to: HttpServerErrorException
[fixed-retry] calling inventory-service for product item-1 (failTimes=2)
[fixed-retry] SUCCEEDED after 3 attempt(s)
```

---

## 5. Interview Angles

**Q: What is the Retry pattern and when should you use it?**

Automatically re-execute a failed operation after a delay to handle transient failures. Use it when:
- Failures are expected to be short-lived (network blip, momentary overload)
- The operation is idempotent (safe to repeat — GET, PUT with same body)
- You have a reasonable bound on retry attempts to avoid runaway loops

Do NOT use retry when failures are permanent (bad request, auth failure, missing resource) — you'll just waste time retrying something that will never work.

---

**Q: Fixed vs Exponential vs Jitter — when do you choose each?**

| Strategy | When to use |
|---|---|
| Fixed | Low concurrency, downstream recovers quickly, predictability needed |
| Exponential | Moderate concurrency, downstream needs time to drain under load |
| Exponential + Jitter | High concurrency (many callers) — production default for most services |

Rule of thumb: **always add jitter in production** if you have > a handful of concurrent callers. The cost is negligible; the thundering herd protection is real.

---

**Q: What is "thundering herd" and how does jitter prevent it?**

When many clients fail at the same moment (e.g. a shared downstream goes down at T=0), they all schedule their next retry at the same interval. Without jitter, 500 clients all retry at T+1s, then T+3s simultaneously — creating synchronized load spikes that can re-crash a service that's just starting to recover.

Jitter adds a random offset to each client's retry delay, spreading those 500 retries across a window (e.g. T+0.5s to T+1.5s) instead of stacking them at one instant. The recovering service sees a smooth ramp rather than a spike.

---

**Q: How does Resilience4j decide which exceptions trigger a retry?**

`retry-exceptions` lists the exceptions that cause a retry attempt. `ignore-exceptions` lists exceptions that pass through without retry. If neither is set, all exceptions are retried.

For HTTP calls: retry on `HttpServerErrorException` (5xx) and `ResourceAccessException` (connection failure). Do not retry on `HttpClientErrorException` (4xx) — those indicate a bug in the request, not a transient server problem.

---

**Q: What's the difference between Retry and Circuit Breaker?**

| | Retry | Circuit Breaker |
|---|---|---|
| Scope | Single call — tries again on failure | Across many calls — stops trying entirely after threshold |
| Response to failure | Wait and retry | Open circuit → fail fast without calling downstream |
| State | No state | CLOSED → OPEN → HALF_OPEN |
| Good for | Transient failures | Sustained failures (dependency is down) |

They're **complementary**: use retry for transient blips; combine with circuit breaker so that if blips become sustained failures, retries stop (no point retrying into an open circuit).

---

**Q: Why must retried operations be idempotent?**

If a POST to charge a payment fails after the charge was applied but before the response was returned, retrying creates a double-charge. Retry is only safe when repeating the call produces the same result as calling once: GET reads, PUT updates to a known state, DELETE of a known resource. Non-idempotent operations (POST create, financial debits) need deduplication keys or must not be retried.

---

**Q: What is `maxAttempts` counting — the retries or the total attempts?**

In Resilience4j: **total attempts**. `maxAttempts=4` means 1 initial attempt + 3 retries. Some older libraries count only retries — always check the docs for the specific library.
