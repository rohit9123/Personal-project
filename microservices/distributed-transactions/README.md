# Distributed Transactions

Topics: 2-Phase Commit · Saga (Orchestration + Choreography) · Idempotency · Outbox Pattern

---

## 1. The Problem

A single database gives you ACID for free — one commit, done. In microservices each service owns its own database. An "order placement" flow might need to:

```
1. Order Service    → save order (DB-1)
2. Payment Service  → charge card (DB-2)
3. Inventory Service → reserve stock (DB-3)
```

No single transaction spans all three. If step 2 succeeds and step 3 fails you have a charged card but no reserved stock — inconsistent state with no automatic rollback.

---

## 2. Two-Phase Commit (2PC)

### What
A distributed protocol that coordinates multiple participants to commit or abort atomically, using a central **coordinator**.

### How

```
              COORDINATOR
                   │
     ┌─────────────┼─────────────┐
     │             │             │
  Svc-A          Svc-B         Svc-C

Phase 1 — Prepare
  Coordinator → "Can you commit?" → all participants
  Each participant: acquire locks, write to WAL, reply READY or ABORT

Phase 2 — Commit (or Abort)
  If ALL replied READY → Coordinator → "Commit" → all participants
  If ANY replied ABORT  → Coordinator → "Abort"  → all participants
```

```
Coordinator        Svc-A        Svc-B        Svc-C
     │──── Prepare ────►│        │            │
     │──── Prepare ─────────────►│            │
     │──── Prepare ──────────────────────────►│
     │◄─── READY ─────────│                   │
     │◄─── READY ──────────────────│           │
     │◄─── READY ────────────────────────────│
     │──── Commit ────►│        │            │
     │──── Commit ─────────────►│            │
     │──── Commit ──────────────────────────►│
```

### Why it fails in microservices

| Problem | Detail |
|---------|--------|
| **Blocking** | Participants hold locks from Phase 1 until they hear the Phase 2 decision. If the coordinator crashes between phases, locks are held forever. |
| **Coordinator SPOF** | One node failure stalls the entire system. |
| **Tight coupling** | Every participant must understand the 2PC protocol and support the prepare/commit/rollback callbacks. |
| **Network latency × N** | Two round trips across N services. Each extra service multiplies the commit latency. |
| **No partial failure model** | 2PC assumes a participant that said READY will always be able to commit. Disk full, OOM, etc. violate this. |

> **Rule of thumb**: 2PC works well within a single data-centre for a handful of databases (XA transactions). It breaks down in microservices because services are independently deployable, failures are the norm, and you cannot hold locks across service boundaries.

---

## 3. Saga Pattern

### What
A Saga is a sequence of **local transactions** where each step publishes an event or sends a command that triggers the next step. If a step fails, a series of **compensating transactions** undo the previous steps.

```
Forward (happy path):
  T1 → T2 → T3 → ... → Tn

On failure at Tk:
  Compensate: C(k-1) → C(k-2) → ... → C1
```

Compensation is not the same as a rollback — it is a **new business operation** that semantically reverses the effect (e.g., "refund payment" instead of deleting a payment row).

### Why over 2PC
- No distributed locks — each local transaction commits immediately.
- Services stay decoupled — no shared transaction coordinator.
- Works across services that may be temporarily unavailable (compensations can be retried).
- Models real-world business processes naturally (cancel, refund, restock are real operations).

---

## 3a. Orchestration-Based Saga

### What
A central **Saga Orchestrator** (a service or a state machine) sends commands to each participant and waits for replies. It owns the entire workflow state.

### How

```
Client
  │
  ▼
Saga Orchestrator
  │
  │──► CreateOrder command  ──► Order Service
  │                                  │ (saves order as PENDING)
  │◄── OrderCreated event  ◄─────────┘
  │
  │──► ProcessPayment command ──► Payment Service
  │                                     │ (charges card)
  │◄── PaymentProcessed event ◄─────────┘
  │
  │──► ReserveInventory command ──► Inventory Service
  │                                       │ (reserves stock)
  │◄── InventoryReserved event ◄──────────┘
  │
  │──► ConfirmOrder command ──► Order Service
                                     │ (marks order CONFIRMED)
```

**On failure** (e.g. Inventory out of stock):

```
  │◄── InventoryFailed event
  │
  │──► RefundPayment command ──► Payment Service   (compensate step 2)
  │◄── PaymentRefunded event
  │
  │──► CancelOrder command ──► Order Service       (compensate step 1)
```

### Orchestrator state machine

The orchestrator persists its own state so it can resume after a crash:

```
STARTED
  → ORDER_CREATED
  → PAYMENT_PROCESSING
  → PAYMENT_DONE
  → INVENTORY_RESERVING
  → INVENTORY_DONE  → COMPLETED
           │
           └─ INVENTORY_FAILED
                → PAYMENT_REFUNDING
                → PAYMENT_REFUNDED
                → CANCELLING
                → CANCELLED
```

### Pros / Cons

| Pros | Cons |
|------|------|
| Clear single place to see the whole flow | Orchestrator becomes a "God service" — knows every participant |
| Easy to add steps or compensations | SPOF if orchestrator goes down (mitigated by persisting state + retries) |
| Easier to debug — one place to trace | More coupling — participants depend on the orchestrator knowing about them |
| Frameworks: Temporal, Conductor, Axon | |

---

## 3b. Choreography-Based Saga

### What
No central coordinator. Each service listens for events from the previous step, does its local transaction, and publishes an event for the next step to react to.

### How

```
Client
  │
  ▼
Order Service ──► [order.created] ──────────────────────►  Kafka
                                                              │
                              Payment Service listens ◄───────┘
                              charges card
                              ──► [payment.processed] ──────► Kafka
                                                                │
                              Inventory Service listens ◄───────┘
                              reserves stock
                              ──► [inventory.reserved] ──────► Kafka
                                                                  │
                              Order Service listens ◄─────────────┘
                              marks order CONFIRMED
```

**On failure** (Inventory out of stock):

```
Inventory Service ──► [inventory.failed] ──────────────────► Kafka
                                                                │
                    Payment Service listens ◄───────────────────┘
                    refunds payment
                    ──► [payment.refunded] ──────────────────► Kafka
                                                                  │
                    Order Service listens ◄───────────────────────┘
                    marks order CANCELLED
```

### Pros / Cons

| Pros | Cons |
|------|------|
| Fully decoupled — services don't know each other | Hard to get a global view of a transaction's progress |
| No SPOF | Compensations are scattered across services — harder to reason about |
| Easy to add new participants (just subscribe to the right event) | Cyclic event dependencies are easy to introduce accidentally |
| Works naturally with Kafka | Harder to test end-to-end |

### Orchestration vs Choreography — when to use

| Use Orchestration when... | Use Choreography when... |
|--------------------------|-------------------------|
| The flow has many steps with complex branching | The flow is simple and linear |
| You need a clear audit trail in one place | Services are truly independent and loosely coupled |
| The team is large — one team owns the saga logic | Each service team can own their own reaction logic |
| Failure paths are complex | Failure is rare and compensation is simple |

---

## 4. Idempotency

### Why it's required
Sagas rely on events/commands being retried on failure. Kafka delivers **at-least-once**. A network timeout does not mean the message was not processed — the service might have saved to DB and then the ack was lost. So the same command may arrive **twice**.

Without idempotency:
```
ReserveInventory command arrives twice
  → stock reserved twice
  → oversell
```

### Strategy 1 — Idempotency key + deduplication table

```
Every command/event carries a unique idempotencyKey (e.g., UUID generated by the sender)

On receive:
  BEGIN TRANSACTION
    SELECT 1 FROM processed_messages WHERE id = :idempotencyKey  → already processed? skip
    -- do business logic (insert/update)
    INSERT INTO processed_messages(id, processed_at) VALUES (:idempotencyKey, NOW())
  COMMIT
```

The check + business logic + insert all happen in **one local transaction**. If the service crashes after the business logic but before the insert, the whole thing rolls back and retries correctly.

```sql
CREATE TABLE processed_messages (
    id          VARCHAR(64) PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL
);
```

### Strategy 2 — Natural key / upsert

If the business entity itself has a natural idempotency key (e.g., `orderId`), use upsert instead of a separate table:

```sql
INSERT INTO reservations (order_id, item_id, quantity)
VALUES (:orderId, :itemId, :qty)
ON CONFLICT (order_id) DO NOTHING;
```

No duplicate row is created regardless of how many times the command arrives.

### Strategy 3 — Version / conditional update

For state machine transitions, guard with a version check:

```sql
UPDATE orders SET status = 'CONFIRMED', version = version + 1
WHERE id = :orderId AND status = 'PAYMENT_DONE' AND version = :expectedVersion;
```

Zero rows affected → already transitioned → treat as idempotent success.

### Rule
**The sender generates the idempotency key. The receiver deduplicates.**

---

## 5. Outbox Pattern

### The Dual-Write Problem

After a local transaction you need to publish an event to the broker. Two naive approaches both have failure windows:

```
Option A — Save first, publish second:
  1. INSERT order into DB  ✓
  2. Crash 💥
  3. Publish order.created to Kafka  ← NEVER HAPPENS
  Result: DB has the order, but downstream services never hear about it.

Option B — Publish first, save second:
  1. Publish order.created to Kafka  ✓
  2. Crash 💥
  3. INSERT order into DB  ← NEVER HAPPENS
  Result: downstream services start processing an order that doesn't exist yet.
```

There is no atomic operation that spans a relational database and a message broker.

### Solution — The Outbox Table

Instead of writing to the broker at all, write to an **outbox table in the same DB transaction** as the business entity. A separate relay process reads from the outbox and publishes to the broker.

```
Step 1: Business transaction (atomic — both or neither)
  BEGIN TRANSACTION
    INSERT INTO orders (id, status, ...) VALUES (...)
    INSERT INTO outbox (id, topic, payload, published) VALUES (newUUID(), 'order.created', '{"orderId":...}', false)
  COMMIT

Step 2: Outbox Relay (separate process)
  SELECT * FROM outbox WHERE published = false ORDER BY created_at
  → for each row: publish to Kafka
  → UPDATE outbox SET published = true WHERE id = :id
```

```
App Service
  ┌─────────────────────────────┐
  │  BEGIN TX                   │
  │   INSERT orders ...         │  ← DB-1
  │   INSERT outbox  ...        │  ← same DB
  │  COMMIT                     │
  └─────────────────────────────┘
              │
              │  (same DB)
              ▼
        outbox table
              │
              │  Relay reads (polling or CDC)
              ▼
           Kafka  ──► Consumers
```

### Relay Implementation A — Polling Publisher

A scheduled job (or background thread) polls the outbox table:

```java
@Scheduled(fixedDelay = 500)
public void relay() {
    List<OutboxEvent> pending = outboxRepository.findByPublishedFalse();
    for (OutboxEvent event : pending) {
        kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload());
        outboxRepository.markPublished(event.getId());
    }
}
```

**Problem**: `send` succeeds but `markPublished` crashes → message sent twice. That's acceptable because:
- Kafka delivery is at-least-once anyway.
- Consumers must be idempotent (see §4).

**Also note**: `send` is async. Use `sendSync` or collect futures and `allOf().get()` before marking published, to avoid marking as published when the broker hasn't actually received it yet.

### Relay Implementation B — Transaction Log Tailing (Debezium)

Instead of polling, read the DB's **binary log** (MySQL binlog / Postgres WAL) and stream INSERT events on the outbox table directly to Kafka. No polling, no extra query load.

```
Postgres WAL ──► Debezium Connector ──► Kafka (outbox topic) ──► Consumers
```

```
App Service        Postgres DB           Debezium               Kafka
    │               outbox table         (CDC connector)
    │──INSERT ─────►│                         │
    │               │──WAL event ────────────►│
    │               │                         │──publish ────────►│
```

Debezium captures the row change from the WAL before it could even poll, so latency is near-zero and there is zero polling overhead on the DB.

### Outbox table schema

```sql
CREATE TABLE outbox (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id  VARCHAR(64)  NOT NULL,      -- orderId, userId etc.
    topic         VARCHAR(128) NOT NULL,      -- Kafka topic to publish to
    payload       JSONB        NOT NULL,      -- the event body
    published     BOOLEAN      NOT NULL DEFAULT false,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at)
    WHERE published = false;
```

---

## 6. Patterns Combined — Order Flow Example

```
OrderService.placeOrder(cmd):
  BEGIN TX
    INSERT orders(id='O1', status='PENDING')
    INSERT outbox(topic='order.created', payload={orderId:'O1', ...})  ← Outbox
  COMMIT

Relay publishes order.created to Kafka

PaymentService consumes order.created:
  BEGIN TX
    SELECT 1 FROM processed_messages WHERE id = cmd.idempotencyKey  ← Idempotency
    → not found, proceed
    charge card
    INSERT processed_messages(id=cmd.idempotencyKey)
    INSERT outbox(topic='payment.processed', payload={orderId:'O1'})  ← Outbox
  COMMIT

Relay publishes payment.processed

InventoryService consumes payment.processed:
  (same pattern — idempotency check + outbox)
  → success → publishes inventory.reserved

OrderService consumes inventory.reserved:
  → updates order to CONFIRMED
```

Every service does: **local ACID transaction + outbox write + idempotency check**. No distributed lock, no 2PC, no shared state.

---

## 7. Comparison

| | 2PC | Saga (Orchestration) | Saga (Choreography) |
|---|---|---|---|
| Consistency | Strong (ACID across nodes) | Eventual | Eventual |
| Coupling | High — all participants need 2PC support | Medium — participants know orchestrator | Low — event contracts only |
| Failure handling | Blocking if coordinator dies | Orchestrator retries commands | Compensating events |
| Visibility | Easy — coordinator sees all | Easy — one service has full state | Hard — distributed across logs |
| Scalability | Poor — locks held across network | Good | Best |
| Use in microservices | Avoid | Common choice | Common choice |

---

## 8. Interview Q&A

**Q: Why can't you use a database transaction across microservices?**
A: Each service owns its own database. A single SQL transaction cannot span two different database connections on two different hosts. You'd need a distributed transaction protocol (2PC/XA), which holds locks across network round trips — this is impractical at microservice scale due to latency, blocking on coordinator failure, and tight protocol coupling.

**Q: What is the difference between 2PC and a Saga?**
A: 2PC achieves strong atomicity by holding locks and coordinating a single distributed commit — all-or-nothing happens at the same instant. A Saga achieves eventual consistency by executing local transactions in sequence — if one fails, compensating transactions roll back the previous steps. Sagas don't hold locks, have no coordinator SPOF, and tolerate temporary unavailability, but they accept a window of inconsistency during execution.

**Q: When would you choose Orchestration over Choreography?**
A: Orchestration when the flow has many steps, complex branching, or you need a single place to observe and manage the saga lifecycle. Choreography when services are truly independent and the flow is simple — adding a new consumer shouldn't require touching the existing ones. In practice, orchestration is easier to debug and compensate because the state machine is in one place.

**Q: What is a compensating transaction and how is it different from a rollback?**
A: A database rollback undoes changes before they are committed — no other system sees them. A compensating transaction is a new, real business operation that semantically reverses an already-committed step. For example, after a payment is committed to the payment service's DB, you cannot rollback it — you must issue a refund. The refund is the compensating transaction. It must be modelled explicitly and may itself fail (requiring retry).

**Q: Why must saga steps be idempotent?**
A: Commands and events in a saga are retried on failure (network timeouts, service restarts, broker redelivery). A consumer cannot always tell whether a previous attempt succeeded before it crashed. Without idempotency, retries cause duplicate effects — charging a card twice, reserving stock twice. Idempotency ensures that processing the same command N times has the same result as processing it once.

**Q: Explain the dual-write problem and how the Outbox Pattern solves it.**
A: After saving business data to the DB, you need to publish an event to the broker. These are two separate systems with no shared transaction. If you save then publish, a crash between the two loses the event. If you publish then save, a crash leaves a published event with no corresponding DB record. The Outbox Pattern writes the event into an outbox table in the **same DB transaction** as the business data. Atomicity is guaranteed by the DB. A separate relay (poller or Debezium CDC) then reads the outbox and publishes to the broker. The relay may publish twice (at-least-once), but consumers are idempotent, so duplicates are harmless.

**Q: What is the difference between a polling publisher and Debezium for the outbox relay?**
A: A polling publisher is a scheduled job that queries `WHERE published = false` on an interval. It adds query load to the DB and has latency equal to the poll interval (typically 500ms–1s). Debezium tails the database's transaction log (binlog/WAL) and streams row changes to Kafka in near-real-time — lower latency, zero polling overhead. Debezium is preferred for high-throughput systems; a polling publisher is simpler to set up for low-volume use cases.

**Q: Can you have a saga within a saga?**
A: Yes — a step in a parent saga can itself trigger a child saga in another bounded context. The parent step waits for the child saga's final outcome event. The key is that each level still uses local transactions and the child saga publishes a completion/failure event back to the parent orchestrator. This nesting works as long as each saga manages its own compensation independently.

**Q: How do you handle the case where a compensating transaction also fails?**
A: This is the "double failure" scenario. Options: (1) retry the compensation with exponential backoff indefinitely — most compensations are eventually possible (a card refund will succeed when the payment network recovers); (2) put the failed compensation into a dead-letter queue for manual intervention; (3) design the system so critical compensations are idempotent and can be retried by an operator. The important thing is to never silently swallow compensation failures — they must be observable (alerts, DLQ, audit log).
