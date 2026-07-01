# Replication Strategies (HLD)

---

## Quick Summary (TL;DR)

- **Replication** copies the same data across multiple machines (replicas) for fault tolerance, read scalability, and geographic latency reduction.
- **Single-leader** (master-slave) is the simplest model: one node accepts writes, followers replicate asynchronously or synchronously. Failover introduces split-brain risk.
- **Multi-leader** allows writes at multiple datacenters but demands conflict resolution (LWW, CRDTs, or custom merge logic).
- **Leaderless** (Dynamo-style) writes to and reads from multiple nodes simultaneously, using quorum math (`W + R > N`) to guarantee freshness.
- **Replication lag** is unavoidable in async setups and causes subtle anomalies: stale reads, non-monotonic reads, and causal ordering violations.

> **Cross-reference**: For a compact comparison table of single-leader vs multi-leader vs leaderless, see [databases.md, "Replication Strategies Compared"](databases.md). For PACELC trade-offs of replicated systems, see [database-picker.md](database-picker.md).

---

## Noob Jargon Buster

* **Replica**: A copy of a database running on a separate machine. If the original dies, a replica can take over.
* **Leader (Primary / Master)**: The one node that accepts all write operations and streams changes to followers.
* **Follower (Secondary / Slave / Read Replica)**: A node that receives a copy of every write from the leader but does not accept writes directly from clients.
* **WAL (Write-Ahead Log)**: An append-only file where the database records every change before applying it to tables. Followers replay this log to stay in sync.
* **Split-Brain**: A dangerous state where two nodes both believe they are the leader and accept conflicting writes.
* **Quorum**: The minimum number of nodes that must agree on a read or write for the operation to succeed.
* **Replication Lag**: The delay between a write being committed on the leader and that write becoming visible on a follower.
* **CRDT (Conflict-free Replicated Data Type)**: A data structure mathematically guaranteed to converge to the same state on all replicas, regardless of the order updates arrive.
* **Merkle Tree**: A hash tree used to efficiently detect which data ranges differ between two replicas without comparing every record.

---

## Real-World Analogy

Think of replication like a chain of bookstores:

- **Single-leader**: There is one headquarters bookstore (the leader) where the master catalog is maintained. Branch stores (followers) receive nightly shipments of updated pages. Customers can browse at any branch, but if they want to add a new book to the catalog, they must go to headquarters.
- **Multi-leader**: There are regional headquarters in New York, London, and Tokyo. Each can accept new book submissions from local authors. Once a week, they sync catalogs — if two regions added a book with the same ISBN, they need a conflict resolution policy (e.g., keep the one submitted first).
- **Leaderless**: Any bookstore can accept new books and any can answer customer queries. To be confident a book exists, you ask three stores simultaneously and go with the majority answer.

---

## 1. Why Replicate?

| Goal | How Replication Helps |
|------|----------------------|
| **Fault tolerance** | If one node crashes, another replica serves traffic with zero data loss (if sync) or minimal loss (if async). |
| **Read scalability** | Spread read queries across N followers instead of overloading a single machine. A system with 10 replicas can theoretically handle 10x read QPS. |
| **Latency reduction** | Place replicas in different geographic regions so users hit a nearby node instead of crossing the ocean (e.g., EU users read from an EU replica). |
| **Disaster recovery** | A replica in a separate datacenter survives a full datacenter outage, enabling fast failover. |

**Key insight**: Replication improves **read** throughput but does NOT improve **write** throughput (every replica must process every write). To scale writes, you need **partitioning/sharding** — see [data-partitioning.md](data-partitioning.md).

---

## 2. Single-Leader Replication (Master-Slave)

The most common replication topology. One node is designated the **leader**; all others are **followers**.

```text
                      ┌─────────────┐
         Writes ────► │   Leader    │
                      │  (Primary)  │
                      └──────┬──────┘
                             │  WAL stream
                   ┌─────────┼─────────┐
                   ▼         ▼         ▼
             ┌──────────┐ ┌──────────┐ ┌──────────┐
  Reads ───► │Follower 1│ │Follower 2│ │Follower 3│
             └──────────┘ └──────────┘ └──────────┘
```

### 2.1 Synchronous vs Asynchronous Replication

| Aspect | Synchronous | Asynchronous | Semi-Synchronous |
|--------|------------|--------------|-------------------|
| **Write latency** | High (waits for follower ACK) | Low (leader returns immediately) | Medium (waits for 1 follower) |
| **Durability** | Guaranteed on N nodes | Risk of data loss if leader crashes before replication | Guaranteed on at least 2 nodes |
| **Availability** | Any follower failure blocks writes | Follower failures invisible to writes | 1 follower failure tolerated |
| **Real-world default** | Rare (too slow) | Most common (MySQL, MongoDB) | PostgreSQL semi-sync, MySQL semi-sync |

**Semi-synchronous** is the pragmatic middle ground: the leader waits for **one** follower to ACK (guaranteeing at least 2 durable copies) while remaining followers replicate asynchronously. If the synchronous follower falls behind, another follower is promoted to synchronous.

### 2.2 Failover: What Happens When the Leader Dies?

```text
Leader dies ──► Detect failure ──► Elect new leader ──► Redirect writes
   (crash)       (heartbeat        (manual or          (update DNS,
                  timeout)          automatic)          connection pool)
```

**Manual failover**: An operator promotes a follower. Slow (minutes to hours) but safe — a human verifies the state before switching.

**Automatic failover** (e.g., MySQL Group Replication, PostgreSQL Patroni, MongoDB elections):

1. **Failure detection**: Followers notice the leader's heartbeat has stopped (typically 10-30s timeout).
2. **Leader election**: Followers run a consensus protocol (Raft, Paxos, or a simpler voting scheme). The follower with the most up-to-date WAL position wins.
3. **Client redirect**: A proxy, DNS update, or virtual IP moves write traffic to the new leader.

### 2.3 The Split-Brain Problem

Split-brain occurs when **two nodes both believe they are the leader** and accept writes independently, causing data divergence.

**How it happens**:
- A network partition isolates the old leader from the cluster.
- The cluster elects a new leader on the other side of the partition.
- The old leader, unaware of the election, continues accepting writes.

**Mitigations**:
1. **Fencing (STONITH)**: The new leader sends a "kill" signal to the old leader's power supply or issues a storage-level fence, ensuring the old leader cannot write to shared storage.
2. **Epoch/term numbers**: Each leader has a monotonically increasing term number. Storage and followers reject writes from a leader with a stale term (used in Raft, ZAB).
3. **Odd-number quorum**: Require a majority of nodes (e.g., 3 of 5) to elect a leader, ensuring only one side of a partition can form a quorum.

---

## 3. Multi-Leader Replication

Each datacenter has its own leader that accepts writes locally. Leaders replicate to each other asynchronously.

```text
     Datacenter A                     Datacenter B
  ┌──────────────────┐            ┌──────────────────┐
  │  Leader A        │◄──────────►│  Leader B        │
  │  ┌────────────┐  │  async     │  ┌────────────┐  │
  │  │ Follower A1│  │  repl.     │  │ Follower B1│  │
  │  └────────────┘  │            │  │ Follower B2│  │
  └──────────────────┘            └──────────────────┘
```

### 3.1 Use Cases

- **Multi-datacenter writes**: Users in the EU write to the EU leader; users in the US write to the US leader. Each gets low-latency writes.
- **Offline-capable clients**: A mobile app acts as a local "leader" that syncs when reconnected (CouchDB, PouchDB).
- **Collaborative editing**: Google Docs-style concurrent edits where each user's client is a "leader."

### 3.2 Conflict Resolution

When two leaders concurrently modify the same record, a **write conflict** occurs.

| Strategy | How It Works | Pros | Cons |
|----------|-------------|------|------|
| **Last-Write-Wins (LWW)** | Attach a timestamp to each write; the highest timestamp wins. | Simple, deterministic. | **Data loss** — earlier concurrent writes are silently discarded. Clock skew worsens this. |
| **CRDTs** | Use mathematically convergent data structures (G-Counter, OR-Set, LWW-Register). | Automatic merge, no data loss for supported types. | Limited data types, higher memory overhead. |
| **Custom merge function** | Application-defined logic (e.g., merge both edits, create a "conflicted" record for human resolution). | Maximum flexibility. | Complex to implement and test. |
| **Conflict-free by design** | Partition writes so each key is "owned" by one leader (e.g., user X always writes to DC A). | No conflicts at all. | Limits write distribution, reduces multi-leader benefits. |

**Cassandra** uses LWW at the cell level. **Riak** supports CRDTs natively. **CouchDB** stores all conflicting revisions and lets the application resolve them.

---

## 4. Leaderless Replication (Dynamo-Style)

No designated leader. Any replica can accept reads and writes. The client (or a coordinator node) sends requests to **multiple replicas** in parallel.

```text
Client ──► ┌──────────┐
       ──► │ Node A   │ ◄── W=2: write succeeds
       ──► │ Node B   │ ◄── when 2 of 3
           │ Node C   │     nodes ACK
           └──────────┘

Client ──► ┌──────────┐
       ──► │ Node A   │ ◄── R=2: read from 2,
       ──► │ Node B   │     return highest
           │ Node C   │     version
           └──────────┘
```

### 4.1 Quorum Reads and Writes

For a cluster with **N** replicas:
- **W** = number of nodes that must ACK a write
- **R** = number of nodes that must respond to a read

**The quorum condition**: `W + R > N` guarantees that at least one node in the read set has the latest write.

| Configuration | N | W | R | Tolerates | Trade-off |
|--------------|---|---|---|-----------|-----------|
| Balanced | 3 | 2 | 2 | 1 node failure (R or W) | Good balance of consistency and availability |
| Write-heavy | 3 | 1 | 3 | 0 failures for reads | Fast writes, slow reads |
| Read-heavy | 3 | 3 | 1 | 0 failures for writes | Fast reads, slow writes |
| Typical prod | 5 | 3 | 3 | 2 node failures | High fault tolerance |

### 4.2 Sloppy Quorum and Hinted Handoff

A **strict quorum** requires W and R from the **designated home nodes** for a key. But what if a home node is temporarily down?

- **Sloppy quorum**: The write is sent to a **non-home node** that temporarily accepts it on behalf of the downed node. The quorum is "sloppy" because it includes nodes outside the key's designated replica set.
- **Hinted handoff**: The non-home node stores the write with a "hint" (metadata indicating the intended recipient). When the downed node recovers, the hint triggers a transfer of the data to its rightful home.

**Trade-off**: Sloppy quorum improves **write availability** (writes succeed even during node failures) but weakens the consistency guarantee — a read quorum might miss the hinted write because it lives on a non-home node.

**Used by**: DynamoDB, Cassandra, Riak.

### 4.3 Anti-Entropy with Merkle Trees

Over time, replicas can drift (missed writes, partial failures). **Anti-entropy** is the background process that detects and repairs these inconsistencies.

```text
Node A Merkle Tree          Node B Merkle Tree
       [Root Hash]                [Root Hash]
       /          \               /          \
   [Hash L]    [Hash R]      [Hash L]    [Hash R'] ← differs!
   /    \      /    \        /    \      /    \
 [H1]  [H2] [H3]  [H4]   [H1]  [H2] [H3'] [H4]
                                        ↑
                                   This range differs,
                                   only sync this subset
```

**How it works**:
1. Each node builds a **Merkle tree** over its data ranges (hash of hashes).
2. Nodes compare root hashes — if equal, they are in sync.
3. If root hashes differ, they recursively compare child hashes to narrow down exactly which key ranges are inconsistent.
4. Only the differing ranges are transferred.

**Efficiency**: Comparing two Merkle trees requires `O(log N)` hash comparisons instead of `O(N)` key-by-key comparisons.

**Used by**: Cassandra (runs `nodetool repair`), DynamoDB, Riak.

---

## 5. Replication Lag and Its Effects

In any async replication setup, there is a window where followers have not yet received the latest write. This **replication lag** (typically milliseconds, occasionally seconds or more) causes several consistency anomalies.

### 5.1 Read-After-Write Consistency (Read-Your-Writes)

**Problem**: A user writes a value, then immediately reads it from a follower that hasn't received the write yet. The user sees stale data and thinks their write was lost.

```text
Time ──────────────────────────────────────────►
Leader:    WRITE x=42 ─────────────────────────►
Follower:  ─────────── lag ──── x=42 arrives ──►
Client:    WRITE x=42 ... READ x → sees x=OLD! (stale)
```

**Solutions**:
- Read from the leader for data the user just wrote (e.g., "read your own profile from leader for 10s after an update").
- Track the client's last write timestamp; route reads to a replica that has caught up past that timestamp.
- Use logical timestamps (LSN / GTID) — the client sends its last-seen position, and the read is routed to a replica at or past that position.

### 5.2 Monotonic Reads

**Problem**: A user makes two successive reads, routed to different followers with different lag. The second read returns **older** data than the first, making it appear as though time went backward.

**Solution**: Pin a user's reads to the same replica (session affinity / sticky sessions), ensuring they always see a monotonically advancing state.

### 5.3 Consistent Prefix Reads (Causal Ordering)

**Problem**: Two causally related writes (e.g., a question and its answer) arrive at a follower in the wrong order, making it appear that the answer came before the question.

**Solution**: Ensure causally related writes go to the same partition, or use causal ordering mechanisms (vector clocks, Lamport timestamps) to enforce ordering across partitions.

---

## 6. Real Systems

| System | Replication Model | Key Details |
|--------|-------------------|-------------|
| **MySQL** | Single-leader | Binlog-based replication. Semi-sync mode (at least 1 replica ACK). Group Replication for automatic failover. GTID-based positioning for replica tracking. |
| **PostgreSQL** | Single-leader | Streaming replication over WAL. Synchronous commit mode configurable per-transaction. Patroni / pg_auto_failover for automatic failover. Logical replication for selective table replication. |
| **MongoDB** | Single-leader (replica set) | Oplog-based replication. Automatic elections via Raft-like protocol. Write concern `w: "majority"` for durability. Read preference configurable (primary, secondary, nearest). |
| **Cassandra** | Leaderless | Tunable consistency (`ONE`, `QUORUM`, `ALL`). Hinted handoff + read repair + anti-entropy repair. Uses Merkle trees for range comparison. LWW conflict resolution at cell level. |
| **DynamoDB** | Leaderless | Quorum-based (configurable strong/eventual reads). Sloppy quorum with hinted handoff. Global tables provide multi-region multi-leader replication with LWW. |
| **CockroachDB** | Single-leader (per-range) | Raft consensus per data range. Automatic leader election and re-replication. Serializable isolation by default. No replication lag (synchronous Raft). |

---

## 7. SDE-2 Interview Angles

**Q1: "You are designing a social media feed. Users write posts and immediately want to see them. How do you handle replication lag?"**

Use **read-after-write consistency**: after a user creates a post, route their subsequent reads to the leader (or a replica known to be caught up) for a short window (e.g., 10 seconds). For other users viewing the feed, eventual consistency is acceptable — they can tolerate a few seconds of lag. Implementation options include tracking a per-user `lastWriteTimestamp` in a session cookie and comparing it against the replica's replication position.

**Q2: "Your system uses single-leader replication. The leader goes down. Walk me through failover."**

1. **Detection**: Followers notice missing heartbeats (typically 10-30s timeout). A monitoring system or consensus quorum confirms the leader is unreachable.
2. **Election**: The follower with the most up-to-date replication position (highest LSN/GTID) is promoted. In systems like PostgreSQL + Patroni, this uses a distributed lock in etcd/ZooKeeper to prevent split-brain.
3. **Redirect**: Update the DNS record, virtual IP, or connection pool (e.g., PgBouncer config) to point to the new leader.
4. **Risk**: If the old leader had unreplicated writes (async replication), those writes are lost. If the old leader comes back online, it must be demoted to a follower and resync — its unreplicated writes are discarded (or saved for manual reconciliation).

**Q3: "When would you choose leaderless over single-leader?"**

Choose leaderless when: (a) you need high **write availability** across regions (no single leader bottleneck), (b) your data model tolerates **eventual consistency** (e.g., shopping cart, user activity logs), and (c) you can afford the operational complexity of quorum tuning and anti-entropy repair. Choose single-leader when you need **strong consistency** (financial transactions, inventory counts) or simpler operational models. In practice, most OLTP systems start with single-leader and only move to leaderless when write scalability demands it.

**Q4: "How does Cassandra detect and repair inconsistencies across replicas?"**

Cassandra uses three mechanisms: (1) **Read repair** — during a quorum read, if one replica returns stale data, the coordinator sends the latest version to the stale replica inline. (2) **Hinted handoff** — if a replica is down during a write, another node stores the write temporarily and forwards it when the downed node recovers. (3) **Anti-entropy repair** — a periodic background process (`nodetool repair`) uses Merkle trees to compare data ranges between replicas, identifying and fixing inconsistencies. In production, operators typically schedule full repairs within Cassandra's `gc_grace_seconds` window (default 10 days) to prevent zombie data from resurrecting deleted records.

**Q5: "Your multi-leader setup has a conflict: two users update the same record in different datacenters simultaneously. How do you resolve it?"**

It depends on the data semantics. For a **last-modified timestamp** field (e.g., user profile bio), **LWW** (Last-Write-Wins) is acceptable because the latest edit is what matters and data loss of the earlier concurrent edit is tolerable. For a **counter** (e.g., like count), use a **CRDT G-Counter** where each datacenter maintains its own counter and the total is the sum — no data is lost. For **complex business data** (e.g., a shopping cart), use a **custom merge function** that unions the items from both carts. The key interview insight is: there is no universal conflict resolution — the strategy depends on what the data represents and what "correct" means for the business.

---

## Traps

1. **"Replication improves write throughput"** — Wrong. Every replica must process every write. Replication scales reads, not writes. To scale writes, you need partitioning/sharding.

2. **"Quorum reads always return the latest value"** — Only if you use a strict quorum with the correct home nodes. With sloppy quorum, the read set might not overlap with the write set, breaking the `W + R > N` guarantee.

3. **"Automatic failover is always better than manual"** — Automatic failover can trigger false positives (temporary network glitch misidentified as leader death), causing unnecessary failovers, split-brain, or data loss. Many production systems (e.g., financial services) prefer manual failover for critical databases.

4. **"LWW is safe because timestamps are accurate"** — Clock skew between nodes can cause the "wrong" write to win. NTP synchronization reduces but does not eliminate skew. Google Spanner uses TrueTime (GPS + atomic clocks) specifically to bound this uncertainty.

5. **"Eventual consistency is too weak for real applications"** — Most user-facing applications tolerate it well. Amazon's shopping cart (Dynamo paper) is the classic example. The key is designing your application to handle the rare stale read gracefully, not avoiding eventual consistency entirely.

---
