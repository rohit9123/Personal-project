# CAP Theorem Deep-Dive (HLD)

## Quick Summary (TL;DR)

- **CAP theorem**: a distributed system can guarantee at most two of Consistency, Availability, and Partition Tolerance -- but since partitions *will* happen, the real choice is **CP vs AP**.
- **Proof intuition**: during a network partition, a node must either block (sacrificing availability to stay consistent) or respond with possibly-stale data (sacrificing consistency to stay available). You cannot do both.
- **PACELC** extends CAP to normal operation: even without partitions, you trade **Latency vs Consistency** -- this is the knob you actually tune daily.
- Consistency is a spectrum, not a binary: Strong > Linearizable > Sequential > Causal > Read-your-writes > Eventual.
- No production system is purely CP or AP -- they are configurable. MongoDB *defaults* CP but can do dirty reads; DynamoDB *defaults* AP but supports `ConsistentRead=true`.

> **Already covered elsewhere**: For a quick CAP/PACELC overview and database classification tables, see [scaling-101.md](scaling-101.md#3-cap-theorem) and [database-picker.md](database-picker.md#pacelc-classification). This note goes deeper on proof intuition, consistency models, and interview technique.

---

## Real-World Analogy

Imagine three bank branches (nodes) in different cities, each holding a copy of your account balance.

- **Consistency**: All branches show the same balance at all times.
- **Availability**: Every branch can process your withdrawal request, even if it cannot reach the other branches.
- **Partition Tolerance**: The branches keep operating even when the phone lines between cities go down.

When the phone lines break (partition):
- **CP (e.g., the cautious bank)**: Branches freeze withdrawals until they can re-sync -- you are turned away, but no one overdraws the account.
- **AP (e.g., the flexible bank)**: Branches let you withdraw from their local ledger -- you get served, but two branches might both let you withdraw, and the account goes negative.

---

## What: The Three Guarantees

| Property | Definition | In Plain English |
|----------|-----------|-----------------|
| **Consistency (C)** | Every read receives the most recent write or an error | All nodes see the same data at the same time |
| **Availability (A)** | Every request to a non-failing node receives a non-error response (no guarantee it's the latest) | The system always responds, even if the data might be stale |
| **Partition Tolerance (P)** | The system continues operating despite arbitrary message loss or delay between nodes | Network splits don't bring the system down |

---

## Why You Must Choose: Proof Intuition

This is not a formal proof, but it gives the interview-ready intuition for *why* CAP is a theorem, not a guideline.

### Setup

Two nodes, N1 and N2, both store variable X (initially X=0). A network partition occurs -- N1 and N2 cannot communicate.

### The Dilemma

1. A client writes X=1 to N1. N1 accepts it but cannot replicate to N2 (partition).
2. Another client reads X from N2.

**Now N2 must choose**:

```
Option A: Respond with X=0 (stale)
  -> Availability: YES (responded)
  -> Consistency: NO  (returned old value)
  -> Result: AP system

Option B: Refuse to respond / return error
  -> Availability: NO  (no response)
  -> Consistency: YES (didn't lie)
  -> Result: CP system

Option C: Respond with X=1 (latest)
  -> IMPOSSIBLE: N2 doesn't know about X=1 (partition!)
```

**There is no Option C during a partition.** This is why you must choose.

### Key Insight

Partition tolerance is not optional -- it is a *fact* of distributed computing (networks fail). So the real question is always: **when a partition happens, do you sacrifice C or A?**

---

## CP vs AP Systems: Real Examples

### CP Systems (Sacrifice Availability During Partition)

| System | How It Achieves CP | What Happens During Partition |
|--------|-------------------|------------------------------|
| **MongoDB** (majority write concern) | Writes require acknowledgment from a majority of replica set members | If primary loses majority, it steps down; writes are rejected until a new primary is elected |
| **ZooKeeper** | Uses ZAB (ZooKeeper Atomic Broadcast) -- a consensus protocol requiring majority quorum | Minority partition becomes read-only; clients connected to minority get `ConnectionLossException` |
| **etcd** | Built on Raft consensus; leader must replicate to majority before acknowledging | Followers in minority partition cannot elect a leader; reads/writes blocked |
| **HBase** | Relies on ZooKeeper for region assignment; RegionServers in minority partition go offline | Regions become unavailable until partition heals and ZK quorum is restored |
| **CockroachDB** | Serializable isolation via Raft per range; leaseholder must contact majority | Ranges without quorum become unavailable; system prefers correctness over responsiveness |

### AP Systems (Sacrifice Consistency During Partition)

| System | How It Achieves AP | What Happens During Partition |
|--------|-------------------|------------------------------|
| **Cassandra** | Peer-to-peer ring with tunable consistency levels; default `ONE` means any single replica can serve reads/writes | Both sides of partition accept writes; conflicts resolved via last-write-wins (LWW) using timestamps |
| **DynamoDB** (default) | Multi-AZ replication with eventually consistent reads by default | Reads may return stale data; writes are accepted and propagated asynchronously |
| **CouchDB** | Multi-master replication with deterministic conflict resolution | Both sides accept writes; conflicts stored as revision trees, resolved on read or compaction |
| **Riak** | Dynamo-style; uses vector clocks (or dotted version vectors) for conflict detection | Siblings created during partition; application resolves on read via `allow_mult=true` |

### The Nuance: Systems Are Configurable

No production system is a hard CP or AP. They offer knobs:

| System | Default | Can Be Tuned To |
|--------|---------|-----------------|
| **Cassandra** | AP (`CL=ONE`) | CP-like with `CL=QUORUM` or `CL=ALL` (at the cost of availability) |
| **DynamoDB** | AP (eventual reads) | Strong consistency per-read via `ConsistentRead=true` |
| **MongoDB** | CP (majority) | AP-like with `w:1` (fire-and-forget writes) or secondary reads |
| **CockroachDB** | CP (serializable) | Cannot be tuned to AP -- consistency is non-negotiable by design |

---

## PACELC Theorem

CAP only describes what happens *during a partition* -- an extreme, infrequent event. **PACELC** asks the more practical question: **what trade-off do you make when the network is healthy (which is 99.9% of the time)?**

```
IF   Partition → choose Availability or Consistency   (the CAP part)
ELSE           → choose Latency    or Consistency     (the daily knob)
```

> For the PACELC classification table of common databases, see [scaling-101.md](scaling-101.md#4-pacelc-theorem) and [database-picker.md](database-picker.md#pacelc-classification).

### Why PACELC Matters More Than CAP in Practice

| Scenario | CAP Says | PACELC Says |
|----------|---------|-------------|
| DynamoDB read with `ConsistentRead=false` | "AP" | **PA/EL** -- during normal operation, you chose **L**atency (read from nearest replica, possibly stale) |
| DynamoDB read with `ConsistentRead=true` | Still "AP" overall | **PA/EC** for this read -- you chose **C**onsistency (read from leader, higher latency) |
| Cassandra with `CL=QUORUM` | Still "AP" overall | **PA/EC** -- you traded latency for consistency even without a partition |
| MongoDB with default read preference | "CP" | **PC/EC** -- consistent both during and outside partitions, but pays with higher write latency |

### The Daily Reality

Most interviews focus on CAP, but the question you answer in production is PACELC:
- "Should this read go to the leader (consistent, slow) or the nearest replica (fast, possibly stale)?"
- "Should this write wait for N replicas to acknowledge (safe, slow) or fire-and-forget (fast, risky)?"

---

## Consistency Models

Consistency is not binary. Here is the spectrum from strongest to weakest:

### 1. Strong Consistency (Linearizability)

- **Definition**: Every read returns the value of the most recent write, as if there were a single copy of the data. Operations appear instantaneous and globally ordered.
- **Cost**: High latency (every read/write must coordinate with a quorum or leader).
- **Example**: CockroachDB serializable transactions, ZooKeeper `sync()` + read.
- **When to use**: Financial transactions, inventory counts, leader election.

### 2. Sequential Consistency

- **Definition**: All nodes see operations in the same order, but that order does not need to match real-time wall-clock order.
- **Difference from linearizability**: Two clients may disagree about *when* an event happened, but they agree on the *sequence*.
- **Example**: ZooKeeper default reads (reads may be served from a follower's log, which is sequentially consistent but may lag the leader).

### 3. Causal Consistency

- **Definition**: If operation A *causally precedes* operation B (e.g., A is a write and B reads A's result), then all nodes see A before B. Concurrent (unrelated) operations may be seen in any order.
- **Example**: MongoDB causal sessions (`causalConsistency=true`). If User A posts a comment and then User B replies, all nodes will see the comment before the reply -- but two unrelated posts may appear in different order on different replicas.
- **When to use**: Social feeds, comment threads, collaborative editing.

### 4. Read-Your-Writes Consistency

- **Definition**: A client is guaranteed to see its own writes. Other clients may see stale data.
- **Implementation**: Route the client's reads to the same node that accepted its writes (sticky sessions), or track a write timestamp and wait for replicas to catch up.
- **Example**: DynamoDB with `ConsistentRead=true` after a write; Facebook's approach for a user's own profile edits.
- **When to use**: User profile updates, shopping cart changes -- anywhere users would be confused by not seeing their own actions.

### 5. Eventual Consistency

- **Definition**: If no new writes occur, all replicas will *eventually* converge to the same value. No guarantee on how long "eventually" takes.
- **Cost**: Lowest latency, highest availability.
- **Example**: DNS propagation, Cassandra with `CL=ONE`, DynamoDB default reads.
- **When to use**: Analytics, recommendation scores, view counters, CDN cache invalidation.

### Comparison Table

| Model | Guarantee | Latency | Example System |
|-------|-----------|---------|---------------|
| Strong (Linearizable) | Latest value, globally ordered | Highest | CockroachDB, Spanner |
| Sequential | Same order on all nodes | High | ZooKeeper (default reads) |
| Causal | Cause-before-effect ordering | Medium | MongoDB causal sessions |
| Read-your-writes | See your own writes immediately | Medium | DynamoDB (per-read flag) |
| Eventual | Converges "eventually" | Lowest | Cassandra CL=ONE, DNS |

---

## How to Answer "Is X a CP or AP System?" in Interviews

This is a common trap question. Here is a structured framework:

### Step 1: State the Defaults

> "By default, [System] behaves as a [CP/AP] system because [mechanism]."

### Step 2: Acknowledge Tunability

> "However, it can be tuned toward [the other side] by adjusting [knob]. For example, ..."

### Step 3: Apply PACELC

> "Under PACELC, [System] is [PA/PC] during partitions and [EL/EC] in normal operation, making it [PA/EL or PC/EC]."

### Step 4: Match to the Use Case

> "For our design, we need [requirement], so we would configure [System] with [setting]."

### Worked Example: "Is Cassandra CP or AP?"

> "Cassandra is AP by default. It uses a peer-to-peer architecture with no single leader, and the default consistency level is ONE -- meaning any single replica can serve a read or accept a write, even during a partition. This makes it highly available but eventually consistent.
>
> However, Cassandra is tunable. Setting consistency level to QUORUM (or LOCAL_QUORUM in multi-DC) requires a majority of replicas to agree, which gives you strong-ish consistency at the cost of availability -- if a quorum is unreachable, the request fails.
>
> Under PACELC, Cassandra at CL=ONE is PA/EL -- available during partitions, and favoring latency over consistency in normal operation. At CL=QUORUM, it shifts toward PA/EC.
>
> For a chat message store where availability matters more than strict ordering, I would use CL=ONE for writes and CL=LOCAL_QUORUM for reads, balancing speed with reasonable consistency."

### Worked Example: "Is MongoDB CP or AP?"

> "MongoDB is CP by default with majority write concern. Writes must be acknowledged by a majority of the replica set before being considered committed. If the primary loses contact with a majority, it steps down, and writes are rejected until a new primary is elected -- sacrificing availability for consistency.
>
> It can be tuned toward AP by using `w:1` write concern and reading from secondaries, but this risks reading stale data and losing acknowledged writes if the primary crashes.
>
> Under PACELC, MongoDB with defaults is PC/EC -- consistent during partitions and consistent in normal operation, at the cost of higher write latency."

---

## Noob Jargon Buster

| Term | Plain English |
|------|--------------|
| **Partition** | A network failure where some nodes can't talk to each other, even though each node is individually healthy |
| **Quorum** | A majority of nodes (e.g., 3 out of 5) that must agree before an operation is considered successful |
| **Replica** | A copy of data on a different node, kept in sync (synchronously or asynchronously) |
| **Leader / Primary** | The single node that accepts writes in a leader-follower architecture |
| **Follower / Secondary** | A node that replicates data from the leader; may serve reads |
| **Split-brain** | When a partition causes two nodes to both believe they are the leader, leading to conflicting writes |
| **Linearizability** | The strongest consistency guarantee -- every operation appears to happen at a single instant in time |
| **Last-write-wins (LWW)** | A conflict resolution strategy where the write with the latest timestamp wins; simple but can lose data |
| **Vector clock** | A data structure that tracks causal ordering of events across nodes, used to detect conflicts without a global clock |
| **Hinted handoff** | A technique where a live node temporarily stores writes intended for a down node, delivering them when it recovers |
| **Read repair** | When a read detects inconsistent replicas, the system updates stale replicas in the background |
| **Anti-entropy** | Background process (e.g., Merkle tree comparison) that detects and repairs divergence between replicas |

---

## Interview Angles

### Q1: "Why can't a distributed system have all three of C, A, and P?"

**Answer**: Partition tolerance is not a choice -- network failures are inevitable in any multi-node system. When a partition occurs, a node that received a write cannot propagate it to the partitioned node. A read to that partitioned node must either (a) return stale data (violating consistency) or (b) refuse to respond (violating availability). There is no third option -- the information physically cannot cross the partition. So the real choice is always CP vs AP *during a partition*.

### Q2: "Your design uses Cassandra for the message store. How do you handle the case where two users see different message orders?"

**Answer**: Cassandra at CL=ONE is eventually consistent, so yes -- two users might temporarily see different orders. For a chat system, I would:
1. Use `CL=LOCAL_QUORUM` for reads to ensure majority agreement within the local datacenter.
2. Use `TIMEUUID` clustering columns so messages are ordered by their Cassandra-assigned time, not client clocks.
3. Accept that cross-DC reads may briefly lag (PACELC EL trade-off) -- for chat, sub-second staleness is acceptable.
4. If strict ordering is critical (e.g., financial ledger), Cassandra is the wrong choice -- use a CP system like CockroachDB or PostgreSQL with synchronous replication.

### Q3: "You said DynamoDB is AP. So it can lose writes?"

**Answer**: DynamoDB does not lose acknowledged writes. Its replication within a single region is synchronous across multiple AZs. The "AP" label refers to its *default read behavior* -- `ConsistentRead=false` may return a slightly stale value because it reads from any replica, not necessarily the one that received the latest write. For writes, DynamoDB uses a consensus protocol internally and only acknowledges after durably storing across multiple AZs. The real risk is with *conditional writes* and *global tables* (multi-region), where conflicts are resolved via last-writer-wins.

### Q4: "When would you choose a CP system over an AP system?"

**Answer**: Choose CP when **correctness is more valuable than responsiveness**:
- **Financial systems**: Double-spending a balance is worse than a brief outage.
- **Inventory/booking systems**: Overselling seats or stock causes real-world harm.
- **Leader election / distributed locks**: A wrong answer (two leaders) is worse than no answer.
- **Configuration management** (ZooKeeper, etcd): Stale config can cause cascading failures.

Choose AP when **availability and low latency matter more than immediate consistency**:
- **Social feeds**: Showing a post 2 seconds late is fine; showing an error page is not.
- **Shopping cart**: Users can tolerate merging a stale cart; they cannot tolerate "service unavailable."
- **Metrics / logging**: Losing a few data points during a partition is acceptable.
- **DNS**: Returning a cached (possibly stale) IP is better than returning nothing.

### Q5: "Explain PACELC with a concrete example."

**Answer**: Take DynamoDB. During a partition (P), it stays available (A) -- both sides of the partition accept writes and resolve conflicts later via last-writer-wins. So the PAC part is **PA**.

Now, in normal operation (E, no partition), DynamoDB gives you a per-read knob:
- `ConsistentRead=false` (default): Reads from any replica -- **fast but possibly stale** (EL).
- `ConsistentRead=true`: Routes to the leader -- **slower but guaranteed fresh** (EC).

So DynamoDB's PACELC classification is **PA/EL** by default, but you can shift individual reads to **PA/EC**. This is the trade-off engineers make on every API call, not just during rare partition events. PACELC captures this daily reality that CAP misses.

---

## Traps

- **"CAP means pick 2 of 3"**: Misleading framing. P is not optional -- partitions happen. The real choice is C vs A *during* a partition. Don't present the "triangle" as three equal choices.
- **"CA is valid for distributed systems"**: It is not. CA only applies to single-node databases. Claiming CA in a distributed design signals a misunderstanding of CAP.
- **"Cassandra is AP so it's always inconsistent"**: No. At `CL=QUORUM`, Cassandra provides strong consistency (R+W > N). The AP label describes the *default* behavior, not the only behavior.
- **"MongoDB is CP so it's always consistent"**: No. With `w:1` and reading from secondaries, you can get stale reads. CP describes the default with majority write concern.
- **"Eventual consistency means data is lost"**: No. It means there is a window where replicas diverge. Given enough time without new writes, all replicas converge. No data is lost -- it just takes time to propagate.
- **"Strong consistency = slow, eventual consistency = fast"**: Oversimplification. Strongly consistent systems can be fast with co-located replicas (e.g., Spanner in a single region). Eventually consistent systems can be slow if the network is congested. The trade-off is about the *worst case*, not the average case.
- **Ignoring PACELC in interviews**: Candidates discuss CAP during partitions but forget that the latency-vs-consistency trade-off exists *all the time*. Mentioning PACELC shows depth and practical awareness.
