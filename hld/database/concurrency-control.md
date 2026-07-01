# Concurrency Control (DB Internals)

---

## Quick Summary (TL;DR)

- The database needs a strategy to run multiple transactions at the same time without them stepping on each other. There are three main approaches.
- **Serial Execution**: Just run one transaction at a time. Sounds slow, but it works surprisingly well for in-memory databases. No concurrency = no concurrency bugs.
- **Two-Phase Locking (2PL)**: Before touching any data, grab a lock. Hold ALL your locks until you commit. This prevents everything but introduces deadlocks and makes the database slower under high load.
- **Serializable Snapshot Isolation (SSI)**: Be optimistic — let everyone run freely, but check at commit time if anything went wrong. If it did, abort and retry. Best of both worlds (speed of snapshot isolation + safety of serializable).

---

## 🤓 Noob Jargon Buster

* **Serializability**: The "gold standard" of correctness. It means: even though transactions ran concurrently, the result is the same as if they had run one after another (in SOME order). No anomalies possible.
* **Shared Lock (Read Lock)**: "I'm reading this — nobody change it while I'm looking." Multiple readers can hold shared locks simultaneously.
* **Exclusive Lock (Write Lock)**: "I'm changing this — nobody else touch it." Only one transaction can hold an exclusive lock. Everyone else waits.
* **Deadlock**: Two transactions are each waiting for the other's lock. Like two people in a narrow hallway — each waiting for the other to move. Neither can proceed.
* **Pessimistic Concurrency**: "I assume conflicts WILL happen, so I'll lock everything upfront." (2PL is pessimistic.)
* **Optimistic Concurrency**: "I assume conflicts are RARE, so I'll just go ahead and check at the end." (SSI is optimistic.)
* **Stored Procedure**: All your transaction logic bundled into one function that runs entirely inside the database. The application sends one call ("run this procedure") instead of multiple back-and-forth queries.

---

## Real-World Analogy

🏦 Imagine a bank with customer files in filing cabinets:

**Serial Execution** = One clerk, one counter. Customers line up. Each customer is served completely before the next one starts. Zero mistakes, but the line can get long.

**Two-Phase Locking** = Multiple clerks, each grabs the filing cabinet drawers they need and HOLDS them until they're completely done. If two clerks need the same drawer, one waits. If Clerk A has Drawer 1 and needs Drawer 2, while Clerk B has Drawer 2 and needs Drawer 1 — **deadlock**. A manager steps in and tells one clerk to start over.

**SSI** = Multiple clerks work freely without locking drawers. They each take notes on what they read. Before finalizing, a supervisor checks: "Would the result be the same if these clerks had worked one at a time?" If not, one clerk is told to redo their work.

---

## 1. Serial Execution

### What Problem Does It Solve?

All concurrency bugs happen because transactions run **at the same time**. What if we just... don't do that?

```
Instead of this (concurrent):          Do this (serial):

  Txn A: ──────────                    Txn A: ──────
  Txn B:    ──────────                 Txn B:       ──────
  Txn C:        ──────                 Txn C:             ──────
  (overlapping = bugs!)                (one at a time = no bugs!)
```

### "Isn't That Incredibly Slow?"

It sounds crazy, but it works for modern in-memory databases because:

1. **RAM is cheap now.** If all your data fits in memory, you never wait for disk I/O. A transaction that would take 10ms with disk access takes **10 microseconds** in memory.

2. **OLTP transactions are tiny.** A typical transaction does a few reads and writes — it finishes in microseconds. At 10μs per transaction, a single thread can do **100,000 transactions per second**.

3. **The bottleneck shifts.** With data in memory, the slow part isn't the CPU — it's the network round-trips between the application and database.

### How It Works

```
                    ┌──────────────────────────┐
                    │     DATABASE SERVER       │
                    │                          │
 Transaction       │  ┌────────────────────┐  │
 Queue:            │  │ Single-threaded    │  │
 [T1, T2, T3] ───→│  │ executor           │  │ ───→ Results
                    │  │ (one at a time)    │  │
                    │  └────────────────────┘  │
                    │                          │
                    │  💾 All data in RAM       │
                    └──────────────────────────┘
```

1. Transactions are submitted to a queue.
2. A **single thread** picks them up one at a time.
3. Each transaction runs completely before the next one starts.
4. Because there's literally no concurrency, there are zero anomalies — the execution IS serial.

### The Stored Procedure Requirement

Here's a critical detail: if the database is single-threaded, it can't afford to sit idle while waiting for the application to send the next query over the network.

```
❌ BAD: Interactive transaction (the database waits for each network round-trip)

  App ──"BEGIN"──────────→ DB (processes, waits for next query)
  App ──"SELECT ..."─────→ DB (processes, waits...)    ← idle time!
  App ──"UPDATE ..."─────→ DB (processes, waits...)    ← idle time!
  App ──"COMMIT"─────────→ DB (done)

  Each network round trip = ~0.5ms of the SINGLE THREAD sitting idle
  That's thousands of transactions that COULD have run!
```

```
✅ GOOD: Stored procedure (one round-trip, DB does everything locally)

  App ──"EXEC transfer(from=1, to=2, amt=100)"──→ DB
  DB: runs everything locally in ~10μs
  DB ──"Done!"──→ App

  One round-trip. The single thread was busy the whole time.
```

**Bottom line:** Serial execution only works if transactions are packaged as **stored procedures** — not interactive back-and-forth queries.

### Scaling Serial Execution with Partitions

"One thread = one CPU core. What if I need more throughput?"

**Answer: Partition the data** so each partition has its own single-threaded executor.

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ Partition 1   │  │ Partition 2   │  │ Partition 3   │
│ Users A–H     │  │ Users I–P     │  │ Users Q–Z     │
│               │  │               │  │               │
│ Thread 1      │  │ Thread 2      │  │ Thread 3      │
│ (serial)      │  │ (serial)      │  │ (serial)      │
└──────────────┘  └──────────────┘  └──────────────┘

Each partition processes its own transactions serially.
Throughput = 3x a single partition! 🎉
```

**But watch out:** If a transaction needs data from **multiple partitions** (e.g., transfer money from User A in Partition 1 to User Q in Partition 3), the database has to coordinate across partitions — which is slow and complex. This destroys the simplicity benefit.

### When to Use Serial Execution

| ✅ Works Well When | ❌ Doesn't Work When |
|-------------------|---------------------|
| Data fits in memory | Dataset is too large for RAM |
| Transactions are short (microseconds) | Transactions involve complex computation or long queries |
| Workload is partitionable | Transactions frequently cross partition boundaries |
| You can write stored procedures | You need interactive transactions |

**Real-world examples:**
- **VoltDB** — built entirely around partitioned serial execution
- **Redis** — single-threaded command execution (each command is atomic)

---

## 2. Two-Phase Locking (2PL)

### What Problem Does It Solve?

Serial execution is too restrictive for most databases (not everything fits in RAM, not everything can be a stored procedure). We want **real concurrency** — multiple transactions running at the same time — but with **zero anomalies**.

2PL achieves this with locks: before you touch any data, grab a lock. Hold all your locks until you're done.

### The Two Phases (It's Simple!)

Every transaction goes through exactly two phases:

```
Phase 1: GROWING 📈              Phase 2: SHRINKING 📉
"Acquire locks as you need them"  "Release ALL locks at once"
─────────────────────────────     ───────────────────────────
You can grab new locks            You release everything
You CANNOT release any locks      You CANNOT grab new locks

      ┌──── Growing Phase ────┐ ┌── Shrinking Phase ──┐
      │                       │ │                     │
 ─────┼──🔒──🔒──🔒──🔒──────┼─┼──🔓🔓🔓🔓───────────┼──→ time
      │  acquire acquire      │ │  release all        │
      │                       │ │  at COMMIT           │
    BEGIN                   COMMIT                   DONE
```

**In practice**, most databases use **Strict 2PL**: you hold ALL locks until COMMIT or ROLLBACK. The "shrinking phase" is just one instant — release everything at once.

### Lock Types and Compatibility

| | Someone holds a **Read Lock** | Someone holds a **Write Lock** |
|---|---|---|
| **You want to Read** | ✅ Go ahead (multiple readers OK) | ❌ Wait (writer might change it) |
| **You want to Write** | ❌ Wait (readers are looking) | ❌ Wait (other writer working) |

**Key difference from MVCC/Snapshot Isolation:**
- In MVCC: Readers NEVER block writers, writers NEVER block readers.
- In 2PL: **Readers block writers AND writers block readers.** This is why 2PL is slower.

### 2PL Step-by-Step Example

```
Transaction A (transfer money):        Transaction B (check balance):
BEGIN;                                  BEGIN;

SELECT balance FROM accounts            
  WHERE id = 1;                        
  🔒 Acquires READ lock on row 1      

                                        SELECT balance FROM accounts
                                          WHERE id = 1;
                                        🔒 Acquires READ lock on row 1
                                        ✅ OK! (multiple read locks allowed)

UPDATE accounts SET balance = 400
  WHERE id = 1;
  🔒 Wants to UPGRADE to WRITE lock
  ❌ BLOCKED! Txn B holds a read lock!

                                        SELECT balance FROM accounts
                                          WHERE id = 2;
                                        🔒 Acquires READ lock on row 2
                                        COMMIT;
                                        🔓 Releases ALL locks

✅ Txn B released lock on row 1!
🔒 Upgrades to WRITE lock on row 1
Proceeds with UPDATE...
COMMIT;
🔓 Releases all locks
```

### Deadlocks (The Inevitable Problem)

When two transactions are each waiting for the other's lock, neither can proceed:

```
Transaction A:                          Transaction B:
BEGIN;                                  BEGIN;

UPDATE accounts WHERE id = 1;           UPDATE orders WHERE id = 100;
  🔒 Write lock on accounts(1)           🔒 Write lock on orders(100)

UPDATE orders WHERE id = 100;           UPDATE accounts WHERE id = 1;
  ❌ BLOCKED (B has it)                   ❌ BLOCKED (A has it)

  A waits for B ─────────────────────── B waits for A

         💀 DEADLOCK! Neither can proceed.
```

**How databases handle this:**

| Strategy | How It Works |
|----------|-------------|
| **Deadlock detection** | Database maintains a "who's waiting for whom" graph. When it finds a cycle → pick a "victim" transaction and abort it. (PostgreSQL, MySQL) |
| **Timeout** | If a transaction waits longer than X seconds for a lock, abort it. Simple but crude. |
| **Prevention (app-level)** | Always acquire locks in the same global order (e.g., by table name, then by row ID). No cycles → no deadlocks. |

### Predicate Locks (Preventing Phantom Reads)

Remember the phantom problem from [transaction-isolation.md](file:///Users/rohit.kumar.4/Documents/interview-prep/hld/database/transaction-isolation.md)? You can't lock a row that doesn't exist yet.

**Predicate locks** solve this by locking a **condition**, not a specific row:

```sql
SELECT * FROM bookings 
  WHERE room = 'A' AND time = '10:00';
-- Predicate lock: "LOCK everything matching room='A' AND time='10:00'"
-- This covers existing rows AND any future rows that would match!
```

If another transaction tries to INSERT a row where `room = 'A' AND time = '10:00'`, it must wait for the predicate lock to be released.

**Problem:** Predicate locks are expensive to check (compare every insert against every active predicate).

### Index-Range Locks (The Practical Approximation)

Instead of locking the exact predicate, lock a **range of the index**:

```
B-Tree index on (room, time):

  ... (A, 09:00) ... (A, 10:00) ... (A, 11:00) ...
       ◄──────── LOCKED RANGE ────────►

Instead of locking EXACTLY "room=A AND time=10:00",
lock the entire range from (A, 09:00) to (A, 11:00).
```

This is coarser (might block some transactions unnecessarily) but much cheaper to implement. MySQL InnoDB calls these **next-key locks**.

### When to Use 2PL

| ✅ Strengths | ❌ Weaknesses |
|-------------|--------------|
| **True Serializability** — prevents ALL anomalies | **Deadlocks** — must detect and resolve them |
| Works with on-disk databases (no RAM requirement) | **Readers block writers** — reduced concurrency |
| Prevents write skew and phantoms | **Latency spikes** — transactions stuck waiting for locks |
| Battle-tested for decades | Lock management adds memory and CPU overhead |

**Used by:** MySQL InnoDB (Serializable level), SQL Server, DB2.

---

## 3. Serializable Snapshot Isolation (SSI)

### What Problem Does It Solve?

We want the **speed of Snapshot Isolation** (readers never block writers) with the **safety of Serializable** (no write skew, no phantoms). 2PL gives us safety but kills performance. SSI gives us both!

### The Big Idea: Optimistic Concurrency

```
2PL (Pessimistic):                    SSI (Optimistic):
─────────────────                     ─────────────────
"Lock everything upfront.             "Let everyone run freely.
 Better safe than sorry."              Check at the end if
                                       anything went wrong."

Prevent conflicts → slow              Detect conflicts → fast (usually)
Low abort rate                         Higher abort rate (but still low)
```

### How SSI Works (Step by Step)

SSI runs on top of snapshot isolation (MVCC) but adds **conflict detection**:

**Step 1:** Transactions run at snapshot isolation (same as before — each sees a frozen snapshot).

**Step 2:** The database **secretly tracks** what each transaction reads and writes.

**Step 3:** At commit time, the database checks: "Has anything happened that would make this transaction's reads stale?"

Let's walk through the on-call doctor example:

```
Alice's Transaction:                    Bob's Transaction:
BEGIN (snapshot at time T);             BEGIN (snapshot at time T);

SELECT COUNT(*) FROM doctors            SELECT COUNT(*) FROM doctors
  WHERE on_call = true;  → 2             WHERE on_call = true;  → 2
  📝 SSI notes: "Alice read              📝 SSI notes: "Bob read
     on_call rows"                          on_call rows"

UPDATE doctors SET on_call = false      UPDATE doctors SET on_call = false
  WHERE name = 'Alice';                   WHERE name = 'Bob';
  📝 SSI notes: "Alice wrote to           📝 SSI notes: "Bob wrote to
     a row that Bob's read depends on"      a row that Alice's read depends on"

COMMIT; → ✅ First to commit wins!

                                        COMMIT; → ❌ ABORT!
                                        SSI detects: "Bob's read of on_call
                                        doctors is now STALE because Alice
                                        changed one of those rows. Bob's
                                        decision was based on outdated info."
                                        Bob must RETRY.
```

### SSI vs 2PL — Side by Side

| Dimension | 2PL (Pessimistic) | SSI (Optimistic) |
|-----------|:-:|:-:|
| **Do readers block writers?** | Yes 🐌 | No 🚀 |
| **Do writers block readers?** | Yes 🐌 | No 🚀 |
| **Deadlocks possible?** | Yes (needs detection) | No (no locks = no cycles) |
| **How are conflicts handled?** | Prevent with locks (wait) | Detect at commit (abort + retry) |
| **Throughput** | Lower (lock contention) | Higher (no blocking) |
| **Abort rate** | Low | Slightly higher (but usually 1–5%) |
| **Best for** | High-contention workloads | Low-to-medium contention |

### When to Use SSI

| ✅ Great For | ❌ Less Ideal For |
|-------------|-------------------|
| Read-heavy workloads (readers never blocked) | Very high contention (too many aborts) |
| When you want Serializable without deadlocks | When aborts are expensive (long transactions) |
| Modern applications with moderate contention | Legacy systems expecting lock-based behavior |

**Used by:**
- **PostgreSQL 9.1+** — `SET TRANSACTION ISOLATION LEVEL SERIALIZABLE` uses SSI (not 2PL!)
- **CockroachDB** — uses SSI for its serializable isolation
- **FoundationDB** — uses similar optimistic concurrency control

---

## 4. The Big Picture — All Three Compared

| Question | Serial Execution | Two-Phase Locking (2PL) | SSI |
|----------|:-:|:-:|:-:|
| **Prevents ALL anomalies?** | ✅ (trivially — no concurrency) | ✅ (locks enforce order) | ✅ (detect at commit) |
| **Can run on-disk data?** | ❌ Needs in-memory | ✅ Yes | ✅ Yes |
| **Needs stored procedures?** | ✅ Yes | ❌ No | ❌ No |
| **Deadlocks possible?** | ❌ No (one thread) | ✅ Yes (must detect) | ❌ No (no locks) |
| **Do readers block writers?** | N/A (no concurrency) | ✅ Yes | ❌ No |
| **Throughput** | Limited (1 core/partition) | Moderate (lock overhead) | High (minimal blocking) |
| **Abort/retry rate** | Zero | Low (deadlock victims) | Moderate (stale reads) |
| **Used by** | VoltDB, Redis | MySQL, SQL Server | PostgreSQL, CockroachDB |

### Decision Helper

```
Q: What do you need?

→ "Max simplicity, data fits in RAM, short transactions"
  → Serial Execution (VoltDB, Redis)

→ "Traditional RDBMS, Serializable, can tolerate lock waits"  
  → Two-Phase Locking (MySQL Serializable, SQL Server)

→ "Modern, fast, Serializable without deadlocks"
  → SSI (PostgreSQL Serializable, CockroachDB)
```

---

## Interview Angles

1. **"What are the two phases in 2PL?"**
   → Growing (acquire locks, never release) and Shrinking (release all locks at once, never acquire). In Strict 2PL, you hold everything until COMMIT.

2. **"How does 2PL differ from the row locks in Read Committed?"**
   → Read Committed releases read locks immediately after reading. 2PL holds them until COMMIT. That's why 2PL prevents non-repeatable reads and write skew — nobody can change data you've read until you're done.

3. **"Why would anyone use serial execution?"**
   → For in-memory databases with short transactions, a single thread handles 100K+ txns/sec with zero locking overhead. No deadlocks, no anomalies, dead simple.

4. **"How do you handle phantom reads in 2PL?"**
   → Predicate locks (expensive, lock a condition) or index-range locks (practical, lock a range of index entries including "gaps" where new rows could be inserted).

5. **"Compare 2PL and SSI."**
   → 2PL is pessimistic (lock upfront, wait for conflicts). SSI is optimistic (run freely, detect conflicts at commit). SSI gives better throughput because readers never block writers, but has a higher abort rate.

6. **"What does PostgreSQL Serializable use vs MySQL Serializable?"**
   → PostgreSQL uses SSI (optimistic, no locking). MySQL uses 2PL with next-key locks (pessimistic, lock-based). Same safety guarantee, completely different implementation.

---

## Common Traps (Don't Say This in Interviews!)

1. ❌ **"2PL = 2PC (Two-Phase Commit)"**
   ✅ Totally different! **2PL** = locking protocol for concurrency (within one database). **2PC** = distributed commit protocol (across multiple databases/services).

2. ❌ **"Serial execution can't scale"**
   ✅ It scales with **partitioning**. If transactions stay within one partition, throughput scales linearly with partition count.

3. ❌ **"2PL prevents deadlocks"**
   ✅ The opposite — 2PL **guarantees** deadlocks will occur (when transactions lock resources in different orders). The database must actively detect and resolve them.

4. ❌ **"SSI aborts most transactions"**
   ✅ In practice, abort rates are low (1–5%) for typical OLTP workloads. Optimistic concurrency works well when contention is moderate.

5. ❌ **"Readers can never block in 2PL"**
   ✅ In 2PL, readers DO block writers and writers DO block readers. This is the key difference from MVCC/SSI, where readers never block anything.

6. ❌ **"You have to choose between correctness and performance"**
   ✅ SSI proves this is a false choice — full Serializability with performance close to Snapshot Isolation for most workloads.

---
