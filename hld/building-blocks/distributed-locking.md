# Distributed Locking (HLD)

## Quick Summary (TL;DR)

- A **distributed lock** ensures that only one process across multiple nodes can access a shared resource at a time -- the distributed equivalent of `synchronized` or `ReentrantLock` in a single JVM.
- **Single-node locks fail** in distributed systems because processes on different machines share no memory or OS-level primitives.
- **Redis-based locks** (`SET key value NX PX ttl`) are the most popular approach. The **Redlock** algorithm extends this across 5+ independent Redis instances for fault tolerance.
- **ZooKeeper-based locks** use **ephemeral sequential znodes** and watches, providing strong ordering guarantees backed by consensus (ZAB protocol).
- **Database-based locks** (`SELECT ... FOR UPDATE`, advisory locks) work for low-throughput cases but add latency and contention on the DB.
- **Fencing tokens** are critical: a monotonically increasing token attached to every lock acquisition, so downstream resources can reject stale writes from a lock holder that outlived its TTL.
- Martin Kleppmann's **critique of Redlock** argues that without fencing tokens, Redlock cannot guarantee correctness -- only efficiency (preventing duplicate work).

---

## Noob Jargon Buster

* **Mutual Exclusion (Mutex)**: A guarantee that at most one thread/process can execute a critical section at any given time.
* **TTL (Time-To-Live)**: An expiration timer on the lock. If the holder crashes or hangs, the lock auto-releases after TTL expires so other processes are not blocked forever.
* **Fencing Token**: A monotonically increasing number issued with each lock acquisition. Downstream storage checks this token to reject writes from stale lock holders.
* **Ephemeral Znode**: A ZooKeeper node that is automatically deleted when the client session that created it disconnects (heartbeat timeout).
* **Split-Brain**: A network partition where two parts of a system each believe they hold the lock simultaneously.
* **Advisory Lock**: A database-level lock that is cooperative -- it only works if all participants voluntarily check it before proceeding.

---

## 1. Why Single-Node Locks Don't Work

On a single JVM, `synchronized` or `ReentrantLock` works because all threads share the same heap memory and OS scheduler.

In a distributed system:
- Processes run on **different machines** with no shared memory.
- Network partitions can isolate processes from the lock coordinator.
- Clocks drift between machines, so time-based expiry is unreliable.
- A process can **pause** (GC, page fault, context switch) and resume after the lock has expired and been acquired by someone else.

> **Analogy:** A single-node lock is like a physical key to a single office door. A distributed lock is like a hotel key-card system -- the central server must coordinate who gets access, revoke cards remotely, and handle guests who lose network connectivity while inside the room.

---

## 2. Redis-Based Locking

### Basic Pattern: SETNX + TTL

```
SET resource_lock <unique_value> NX PX 30000
```

- `NX` -- only set if the key does **not** already exist (mutual exclusion).
- `PX 30000` -- auto-expire after 30 seconds (deadlock prevention).
- `<unique_value>` -- a UUID owned by the acquiring client, used to ensure only the owner can release the lock.

#### Releasing the Lock (Lua Script for Atomicity)

```lua
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
else
    return 0
end
```

The `GET + DEL` must be atomic. Without the Lua script, another client could acquire the lock between your GET and DEL, and you would accidentally delete their lock.

### The Problem: Single Redis Instance

If the single Redis node crashes or becomes unreachable, the lock is lost. Failover to a replica is unsafe because Redis replication is **asynchronous** -- the replica may not have received the lock key yet.

```
Client A acquires lock on master
Master crashes BEFORE replicating the key
Replica is promoted to master
Client B acquires the same lock  --> VIOLATION
```

---

### Redlock Algorithm (Multi-Node)

Proposed by Salvatore Sanfilippo (antirez), Redlock uses **N independent Redis masters** (typically N = 5, no replication between them).

#### Steps:

1. **Get current time** (T1).
2. **Try to acquire the lock** on all N instances sequentially, using the same key and unique value, with a small per-instance timeout (e.g., 5-50ms).
3. **Calculate elapsed time** (T2 - T1). The lock is considered acquired only if:
   - The client acquired the lock on **a majority** (at least N/2 + 1 = 3 out of 5 instances), AND
   - The total elapsed time is **less than the lock TTL**.
4. **Effective lock validity** = TTL - elapsed time.
5. If the lock was NOT acquired on a majority, **release the lock on all instances** (even those where it was acquired).

```
         Client
         |  |  |  |  |
     [R1] [R2] [R3] [R4] [R5]    (5 independent Redis masters)
      OK   OK   OK   FAIL FAIL   --> 3/5 = majority, lock acquired
```

#### Why N = 5?

With 5 nodes, the system tolerates **2 node failures** and still achieves a quorum of 3. With 3 nodes, only 1 failure is tolerable.

---

## 3. ZooKeeper-Based Locking

ZooKeeper provides stronger guarantees because it is built on a consensus protocol (ZAB). Locks use **ephemeral sequential znodes**.

### Algorithm: Ephemeral Sequential Nodes

1. Client creates an **ephemeral sequential** znode under `/locks/resource`:
   ```
   create -e -s /locks/resource/lock-
   ```
   ZooKeeper appends a monotonically increasing sequence number: `/locks/resource/lock-0000000001`.

2. Client calls `getChildren(/locks/resource)` to list all children.

3. If the client's znode has the **lowest sequence number**, it holds the lock.

4. Otherwise, the client sets a **watch** on the znode with the **next-lower sequence number** (not on all children -- this avoids the **herd effect**).

5. When the watched znode is deleted (lock released or session expired), the client re-checks if it now has the lowest sequence number.

```
/locks/resource/
  lock-0000000001  <-- Client A (holds lock)
  lock-0000000002  <-- Client B (watches 0001)
  lock-0000000003  <-- Client C (watches 0002)
```

### Why Ephemeral Nodes?

If a client crashes, its ZooKeeper session eventually times out (heartbeat missed), and the ephemeral znode is **automatically deleted**. The next client in the queue is notified and acquires the lock. No TTL guessing required.

### Herd Effect Prevention

A naive approach watches the lock parent node for any child change -- this causes **all** waiting clients to wake up and re-check simultaneously (thundering herd). Watching only the predecessor znode means exactly **one** client is notified per lock release.

---

## 4. Database-Based Locking

### Option A: SELECT ... FOR UPDATE

```sql
BEGIN;
SELECT * FROM locks WHERE resource_id = 'inventory-42' FOR UPDATE;
-- Critical section: do work
COMMIT;  -- releases the row-level lock
```

The row-level lock is held for the duration of the transaction. Other transactions attempting the same `SELECT FOR UPDATE` will **block** until the first transaction commits or rolls back.

**Drawbacks:**
- Ties lock duration to DB transaction duration -- long-running work holds a connection.
- Deadlocks if multiple resources are locked in different orders.
- DB connection pool exhaustion under high contention.

### Option B: Advisory Locks (PostgreSQL)

```sql
SELECT pg_advisory_lock(hashtext('inventory-42'));
-- Critical section
SELECT pg_advisory_unlock(hashtext('inventory-42'));
```

Advisory locks are **cooperative** -- they do not block reads or writes on actual tables. They are lighter than row-level locks but rely on every participant calling `pg_advisory_lock` before accessing the resource.

### Option C: Insert-Based Lock Table

```sql
CREATE TABLE distributed_locks (
    resource_id   VARCHAR(255) PRIMARY KEY,
    locked_by     VARCHAR(255) NOT NULL,
    locked_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMP    NOT NULL
);

-- Acquire: INSERT fails if resource_id already exists
INSERT INTO distributed_locks (resource_id, locked_by, expires_at)
VALUES ('inventory-42', 'worker-7', NOW() + INTERVAL '30 seconds');

-- Release
DELETE FROM distributed_locks
WHERE resource_id = 'inventory-42' AND locked_by = 'worker-7';
```

A background reaper process periodically deletes rows where `expires_at < NOW()` to handle crashed lock holders.

---

## 5. Fencing Tokens -- Why TTL Alone Is Not Enough

TTL-based locks have a fundamental flaw:

```
Timeline:
  T=0    Client A acquires lock (TTL = 30s)
  T=5    Client A enters long GC pause (or network partition)
  T=30   Lock expires (TTL)
  T=31   Client B acquires lock, writes X = 200
  T=35   Client A wakes from GC, believes it still holds the lock
  T=36   Client A writes X = 100  --> OVERWRITES Client B's write!
```

### The Solution: Fencing Tokens

Every lock acquisition returns a **monotonically increasing token** (e.g., a ZooKeeper zxid or a Redis counter).

```
Client A acquires lock --> fencing token = 33
Client B acquires lock --> fencing token = 34

Storage server checks:
  Client A writes with token 33
  Client B writes with token 34
  Client A (stale) writes with token 33 --> REJECTED (33 < 34)
```

The downstream storage (database, file system, message queue) must **compare tokens** and reject any write with a token lower than the highest it has already seen.

> **Key insight:** Fencing tokens shift the safety guarantee from the lock service to the storage layer. Even if the lock service is imperfect, the storage server enforces correctness.

---

## 6. Martin Kleppmann's Critique of Redlock

In his 2016 blog post *"How to do distributed locking"*, Kleppmann argued that Redlock is fundamentally flawed for **correctness** use cases.

### Two Reasons to Use Distributed Locks

| Purpose | If lock fails... | Example |
|---------|-----------------|---------|
| **Efficiency** | Duplicate work is done (wasteful but harmless) | Preventing two cron jobs from running the same batch |
| **Correctness** | Data corruption, lost writes, inconsistency | Preventing two processes from writing to the same file |

### Kleppmann's Arguments Against Redlock for Correctness

1. **Process pauses defeat TTL**: A GC pause, page fault, or network delay can cause a client to operate past its lock expiry without knowing it. Redlock has no mechanism to notify the client that its lock has expired.

2. **Clock assumptions are unsafe**: Redlock relies on clocks across N Redis instances being roughly synchronized. Clock jumps (NTP corrections, VM live migrations, leap seconds) can cause the lock TTL to expire prematurely or extend incorrectly.

3. **No fencing token support**: Redlock does not issue monotonically increasing fencing tokens. Without them, there is no way for downstream storage to reject stale writes.

### Kleppmann's Recommendation

- For **efficiency**: A single Redis `SETNX` lock is sufficient. If it occasionally fails, no harm done.
- For **correctness**: Use a proper consensus system (ZooKeeper, etcd) that provides **fencing tokens** (e.g., ZooKeeper's `zxid` or sequential znode number). The lock is only as safe as the fencing mechanism in the storage layer.

### Antirez's Rebuttal

Salvatore Sanfilippo (antirez) argued that:
- In practice, clocks on well-maintained servers do not jump significantly.
- Redlock is designed for cases where you need *better than single-node* guarantees but don't want the operational overhead of ZooKeeper.
- The debate remains unresolved; the community generally sides with Kleppmann for correctness-critical systems.

---

## Comparison of Approaches

| Criteria | Redis (SETNX) | Redlock | ZooKeeper | Database |
|----------|---------------|---------|-----------|----------|
| **Latency** | ~1ms | ~5-10ms (5 RTTs) | ~10-50ms | ~5-20ms |
| **Fault tolerance** | None (single node) | Tolerates minority failures | Tolerates minority failures | Depends on DB HA setup |
| **Fencing tokens** | No (manual) | No (manual) | Yes (zxid / seq number) | Manual (counter column) |
| **Auto-release on crash** | TTL only | TTL only | Ephemeral node (session timeout) | Transaction rollback / reaper |
| **Consistency guarantee** | Best-effort | Debated (Kleppmann critique) | Strong (consensus-backed) | Strong (ACID) |
| **Operational complexity** | Low | Medium (5 independent instances) | High (ZK ensemble) | Low (reuse existing DB) |
| **Best for** | Efficiency locks | Efficiency locks (improved) | Correctness locks | Low-throughput correctness |

---

## Interview Angles

**Q1: You need to prevent two instances of a payment service from processing the same payment. What locking strategy do you use?**

This is a **correctness** use case -- double-charging a customer is unacceptable. Use ZooKeeper (or etcd) with ephemeral sequential znodes and fencing tokens. The payment storage layer must validate the fencing token before writing. A single Redis SETNX is insufficient because a GC pause could cause a stale client to process the payment after the lock expires.

**Q2: What is wrong with using a Redis lock with just SETNX and TTL?**

Three problems: (1) If the lock holder crashes, other clients must wait for the full TTL to expire. (2) If the lock holder's operation takes longer than the TTL, the lock expires while work is still in progress, and another client enters the critical section. (3) No fencing token is issued, so downstream storage cannot reject stale writes from the original holder.

**Q3: Explain the Redlock algorithm and its weakness.**

Redlock acquires a lock on a majority (N/2 + 1) of N independent Redis masters. It calculates the effective validity as TTL minus the time spent acquiring. The weakness (per Kleppmann) is that it relies on bounded clock drift and bounded process pauses -- neither of which can be guaranteed. A GC pause after acquiring the lock can cause the client to operate past expiry, and Redlock provides no fencing mechanism.

**Q4: How does ZooKeeper avoid the "thundering herd" problem with locks?**

Instead of all waiting clients watching the lock parent node, each client watches only the znode with the **next-lower sequence number** (its immediate predecessor). When a lock is released, only the single next-in-line client is notified, not all waiters. This converts O(N) notifications per release to O(1).

**Q5: What is a fencing token and why is it necessary?**

A fencing token is a monotonically increasing integer issued with each lock acquisition. It is necessary because TTL-based locks can be violated by process pauses, network delays, or clock drift. The downstream storage server records the highest token it has seen and rejects any write with a lower token, preventing stale lock holders from corrupting data. Without fencing tokens, no distributed lock can guarantee correctness -- the safety property is enforced by the storage layer, not the lock service.

---

## Traps

- **Forgetting the Lua script for Redis unlock.** A plain `GET` followed by `DEL` is not atomic. Between the two commands, another client can acquire the lock, and you delete their lock. Always use a Lua script or `EVAL`.
- **Setting TTL too short.** If your critical section sometimes takes longer than the TTL (due to network calls, DB latency, or GC pauses), the lock expires while you are still working. Another client enters, and you get a race condition. Either use a lock renewal (watchdog) thread or switch to ZooKeeper's session-based model.
- **Thinking Redlock gives you strong correctness.** It does not. It is an improvement over a single Redis node for efficiency locks but is not a substitute for a consensus-backed system when correctness is required.
- **Ignoring fencing tokens.** The most common interview mistake. Candidates describe a locking mechanism but forget to explain how the storage layer rejects stale writes. Always mention fencing tokens when discussing correctness.
- **Using the database as a lock service under high throughput.** `SELECT ... FOR UPDATE` holds a DB connection and a row-level lock for the entire critical section. Under high contention, this exhausts the connection pool and causes cascading timeouts.
- **Watching all children in ZooKeeper.** This causes the herd effect -- every client wakes up on every lock release, only for N-1 of them to go back to sleep. Watch only the predecessor znode.
- **Confusing lock release with lock expiry.** Explicit release (DEL or znode delete) is immediate. TTL expiry is a safety net for crashes, not a normal release mechanism. Design your system so that locks are explicitly released in the happy path.
