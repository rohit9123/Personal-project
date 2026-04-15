# Bulkhead Pattern

---

## 1. What

The **Bulkhead pattern** isolates the resources used to call different downstream services so that a failure or slowdown in one dependency cannot exhaust the resources needed by other dependencies.

Named after the watertight compartments in a ship's hull — if one compartment floods, the others stay dry and the ship stays afloat.

---

## 2. Why

In a microservices system, a single service often calls multiple downstream services.

**The problem without bulkheads:**

```
Order Service has 200 shared Tomcat threads.
Payment Service becomes slow (responds in 30s instead of 200ms).
100 concurrent users hit /order → 100 threads block waiting for payment.
Inventory calls also need threads → none available → they queue or fail.
The entire Order Service is now unresponsive, even though Inventory is perfectly healthy.
```

**The bulkhead fixes this:**

```
Payment calls → capped at 2 threads (their own pool)
Inventory calls → capped at 5 concurrent semaphore permits

Payment goes slow → only 2 threads affected
Inventory calls → still have their own 5 permits → keep working
Order Service stays partially functional
```

---

## 3. How It Works

Resilience4j provides two bulkhead types:

### ThreadPoolBulkhead

- Runs the protected method on a **dedicated thread pool** (separate from Tomcat's thread pool)
- Caller's thread is freed immediately; the call runs asynchronously
- Config: `max-thread-pool-size`, `queue-capacity`
- When pool + queue full → `BulkheadFullException` thrown before calling downstream
- Returns `CompletableFuture<T>` — method must be async

```
Request thread → submits task to payment-thread-pool (size=2)
                 → if pool full AND queue full → BulkheadFullException (fallback)
                 → otherwise runs in dedicated thread → returns CompletableFuture
```

### SemaphoreBulkhead

- Uses a **counting semaphore** — no extra threads, caller's thread is used
- Acquires a permit before proceeding, releases it when done
- Config: `max-concurrent-calls`, `max-wait-duration`
- When all permits taken and wait=0 → `BulkheadFullException` immediately
- Synchronous — simpler, lower overhead, but caller thread blocks for the duration

```
Request thread → tries to acquire permit (max=5)
                 → if none available and max-wait=0 → BulkheadFullException (fallback)
                 → otherwise calls inventory directly on same thread
```

### Choosing between them

| | ThreadPoolBulkhead | SemaphoreBulkhead |
|---|---|---|
| Extra threads | Yes (dedicated pool) | No |
| Caller thread freed | Yes (async) | No (blocks) |
| Overhead | Higher | Lower |
| Best for | Slow I/O calls where you want the caller freed | Fast calls where you just need a concurrency cap |
| Return type | `CompletableFuture<T>` | `T` (synchronous) |

---

## 4. Code

| File | What it is |
|------|-----------|
| `order-service/OrderController.java` | Entry point — calls both downstream services per request |
| `order-service/PaymentClient.java` | `@Bulkhead(type=THREADPOOL)` — payment calls in dedicated pool |
| `order-service/InventoryClient.java` | `@Bulkhead(type=SEMAPHORE)` — inventory calls with concurrent limit |
| `order-service/application.yml` | Resilience4j config — pool sizes, queue capacity, semaphore limits |
| `payment-service/PaymentController.java` | Has `?slow=true` param to simulate a slow dependency |
| `inventory-service/InventoryController.java` | Always fast — shows isolation is working |

### Run order

```bash
# Terminal 1 — inventory-service (port 8082)
cd inventory-service && mvn spring-boot:run

# Terminal 2 — payment-service (port 8081)
cd payment-service && mvn spring-boot:run

# Terminal 3 — order-service (port 8080)
cd order-service && mvn spring-boot:run
```

### Demo — observe the bulkhead in action

```bash
# Normal request — both calls fast
curl http://localhost:8080/order/1

# Slow payment — fill the bulkhead (run 4+ of these concurrently)
# Requests 1-2: served by the 2 payment threads
# Request 3: queued (queue-capacity=1)
# Request 4+: BulkheadFullException → fallback response immediately
curl "http://localhost:8080/order/1?slowPayment=true" &
curl "http://localhost:8080/order/2?slowPayment=true" &
curl "http://localhost:8080/order/3?slowPayment=true" &
curl "http://localhost:8080/order/4?slowPayment=true" &

# Inventory still works even while payment is clogged
curl http://localhost:8080/order/99
```

### Actuator endpoints

```bash
# View bulkhead states
curl http://localhost:8080/actuator/bulkheads
curl http://localhost:8080/actuator/threadpoolbulkheads
```

---

## 5. Interview Angles

**Q: What problem does the Bulkhead pattern solve?**

Resource exhaustion cascade: one slow dependency monopolises shared resources (threads, connections) and takes down unrelated functionality. Bulkheads prevent one dependency's failure from propagating to others.

---

**Q: ThreadPoolBulkhead vs SemaphoreBulkhead — when do you use each?**

| | ThreadPoolBulkhead | SemaphoreBulkhead |
|---|---|---|
| Use when | Downstream calls are slow or blocking I/O | Need a concurrency cap with low overhead |
| Caller thread | Freed immediately (async) | Blocked for the call's duration |
| Cost | Extra threads + context switching | Just a counter |

Rule of thumb: use ThreadPoolBulkhead for slow external calls (payment, external APIs), SemaphoreBulkhead for fast internal calls or DB queries where you just want to cap concurrency.

---

**Q: How does the fallback work?**

`@Bulkhead(fallbackMethod = "myFallback")` — when `BulkheadFullException` is thrown, Resilience4j calls the fallback method instead. The fallback must have the same parameters as the original method, plus a `Throwable` at the end.

---

**Q: Bulkhead vs Circuit Breaker — what's the difference?**

| | Bulkhead | Circuit Breaker |
|---|---|---|
| Protects against | Resource exhaustion from concurrency | Cascading failures from repeated errors |
| Mechanism | Limits concurrent calls | Opens circuit after error threshold |
| Reacts to | Too many simultaneous calls | Too many failed calls |
| State | No open/closed state | CLOSED → OPEN → HALF_OPEN |

They're complementary — use both. Bulkhead limits how many calls are in-flight; circuit breaker stops calling if those calls keep failing.

---

**Q: What happens to rejected requests when the bulkhead is full?**

By default, `BulkheadFullException` is thrown synchronously (for semaphore) or the CompletableFuture completes exceptionally (for thread pool). You handle this via a fallback method — typically return a degraded response rather than an error to the user.

---

**Q: How does Bulkhead relate to the Hystrix thread pool isolation?**

Hystrix's thread isolation was the original implementation of ThreadPoolBulkhead. Hystrix is now deprecated; Resilience4j is the successor. The concept is identical — each dependency gets its own thread pool.
