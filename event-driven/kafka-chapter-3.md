# Kafka — Chapter 3: Controllers, KRaft vs Zookeeper & ISR

Topics covered: Controller · KRaft vs ZooKeeper · In-Sync Replicas (ISR)

---

## 1. Controller

### What

The **Controller** is one special broker in a Kafka cluster that is elected to
perform cluster-wide administrative duties. Every broker can potentially become
the Controller, but only one is active at a time.

### Responsibilities

| Responsibility | Detail |
|----------------|--------|
| **Partition leader election** | When a leader broker crashes, the Controller picks a new leader from the ISR and pushes the updated metadata to all brokers. |
| **Broker lifecycle tracking** | Detects when brokers join or leave the cluster (via ZooKeeper watches or the KRaft quorum). |
| **Topic/partition CRUD** | Handles `CreateTopics`, `DeleteTopics`, `AlterPartitionReassignments` admin requests. |
| **Metadata propagation** | Pushes the cluster state (partition→leader mapping) to all other brokers via `LeaderAndIsr` and `UpdateMetadata` RPCs. |

### How Election Works

**ZooKeeper mode (pre-KRaft):** every broker races to create an ephemeral
`/controller` znode. The first to succeed becomes Controller. If the Controller
dies, ZooKeeper deletes the ephemeral node, which triggers a watch on all other
brokers — they race again.

**KRaft mode:** the Controller is elected by a Raft quorum among a designated
set of controller nodes. No ZooKeeper involved.

---

## 2. KRaft vs ZooKeeper

### The Problem with ZooKeeper

Up to Kafka 2.x, Kafka stored all cluster metadata (broker list, topic configs,
partition assignments, ACLs) in an external **ZooKeeper** ensemble. This created
several pain points:

- **Operational complexity** — two separate distributed systems to deploy,
  monitor, upgrade, and secure (Kafka + ZooKeeper).
- **Metadata bottleneck** — on startup every broker had to fetch the full
  cluster state from ZooKeeper. At scale (millions of partitions) this caused
  slow restarts and Controller failover latency in the tens of seconds.
- **Split-brain risk** — Controller and ZooKeeper had to stay in sync; network
  partitions could create inconsistencies.
- **Scalability ceiling** — ZooKeeper's watch model degraded beyond ~200k
  partitions.

### KRaft — Kafka Raft Metadata Mode

Introduced in Kafka 2.8 (preview), **production-ready from Kafka 3.3**, and
ZooKeeper **removed entirely in Kafka 4.0**.

KRaft replaces ZooKeeper by storing cluster metadata in a special **internal
Kafka topic** called `__cluster_metadata` (a single-partition, replicated log),
managed by a Raft consensus quorum built into Kafka itself.

#### Architecture

```
 ┌──────────────────────────────────────────┐
 │           KRaft Quorum                   │
 │  ┌──────────┐  ┌──────────┐  ┌────────┐ │
 │  │Controller│  │Controller│  │Ctrl    │ │  ← dedicated controller nodes
 │  │  node 1  │  │  node 2  │  │node 3  │ │    (or combined broker+controller)
 │  └────┬─────┘  └──────────┘  └────────┘ │
 │       │  Active Leader (Raft)            │
 └───────┼──────────────────────────────────┘
         │  Metadata replication via __cluster_metadata log
         ▼
 ┌───────────────────────────────┐
 │   Broker nodes (data plane)   │
 │   broker-1  broker-2  broker-3│
 └───────────────────────────────┘
```

#### How Raft is Used

The active Controller (Raft leader) appends metadata changes (topic creation,
leader election results) as records to `__cluster_metadata`. Follower
controllers replicate via Raft. Brokers subscribe to this log and maintain an
in-memory cache — no more full state fetch on startup, just replay from last
known offset.

#### Comparison Table

| Dimension | ZooKeeper Mode | KRaft Mode |
|-----------|---------------|------------|
| External dependency | ZooKeeper ensemble required | None — Kafka only |
| Metadata store | ZooKeeper znodes | `__cluster_metadata` Kafka log |
| Controller election | ZooKeeper ephemeral znode race | Raft leader election |
| Controller failover | Seconds (ZK watch + broker race) | Milliseconds (Raft re-election) |
| Partition scale | ~200k partitions practical limit | Millions of partitions |
| Startup time | O(partitions) — full ZK fetch | O(lag) — replay from last offset |
| Operational cost | Two systems to manage | Single system |
| Available since | Always | Preview 2.8, GA 3.3, only mode in 4.0 |

#### Node Roles in KRaft

A node can be configured as:

- **`broker`** — handles data (produce/consume). Follows the active Controller.
- **`controller`** — participates in the Raft quorum, no data.
- **`broker,controller`** — combined role; fine for small/dev clusters, not
  recommended for large production clusters (controller work can impact I/O
  latency).

`server.properties`:
```properties
# Pure broker
process.roles=broker
node.id=1
controller.quorum.voters=1@controller-1:9093,2@controller-2:9093,3@controller-3:9093

# Pure controller
process.roles=controller
node.id=1
```

---

## 3. ISR — In-Sync Replicas

### What

For each partition, Kafka maintains a **leader** replica and zero or more
**follower** replicas. Not all followers are equally up-to-date at any given
instant. The **ISR (In-Sync Replica set)** is the subset of replicas that are
considered sufficiently caught up with the leader.

```
Partition 0 — replicas on broker-1 (leader), broker-2, broker-3
ISR = [broker-1, broker-2, broker-3]   ← all caught up

If broker-3 lags:
ISR = [broker-1, broker-2]             ← broker-3 evicted from ISR
```

### How a Replica Stays "In Sync"

A follower is in the ISR if it has fetched up to the leader's log end offset
within the window defined by:

```properties
replica.lag.time.max.ms=30000   # default 30 s
```

If a follower hasn't issued a fetch request within this window, the Controller
evicts it from the ISR. When it catches up again, the Controller re-adds it.

### Why ISR Matters — Durability Guarantee

Kafka only acknowledges a produce request as committed once **all ISR members**
have written the record (when `acks=all`). This is the key durability guarantee:
a message in the ISR is safe even if any one broker fails.

```
Producer ──acks=all──► Leader (broker-1)
                           │
                           ├─ replicates ──► broker-2  ✔
                           └─ replicates ──► broker-3  ✔
                                              (both in ISR)
                           ▼
                    ACK sent to producer
```

If `acks=1`, only the leader write is confirmed — fast but data can be lost if
the leader crashes before followers replicate.

### `min.insync.replicas`

```properties
min.insync.replicas=2   # topic-level or broker-level default
```

When `acks=all`, the broker rejects the produce request with
`NotEnoughReplicasException` if the ISR size falls below `min.insync.replicas`.
This prevents a false sense of durability when most replicas have fallen behind.

| Setting | Meaning |
|---------|---------|
| `replication.factor=3` | 3 copies of each partition across brokers |
| `min.insync.replicas=2` | At least 2 replicas (including leader) must be in ISR to accept writes |
| `acks=all` | Producer waits for all ISR members to confirm |

The combination `replication.factor=3, min.insync.replicas=2, acks=all` is the
standard production durability recipe: tolerates 1 broker failure without data
loss, and refuses writes rather than silently losing data if 2 brokers are down.

### Leader Election and ISR

When a partition leader crashes, the Controller elects the new leader
**only from the current ISR**. This guarantees that the new leader has all
committed messages.

```
ISR = [broker-1 (leader), broker-2]
broker-1 crashes →
  Controller elects broker-2 as new leader
  (broker-2 has all committed records by definition of ISR)
```

If the ISR has only one member and that broker also fails, Kafka faces a choice:

- **Wait for an ISR member to come back** (safe but unavailable — `unclean.leader.election.enable=false`, default).
- **Elect any replica** even if out of sync, risking data loss (`unclean.leader.election.enable=true`).

### HW — High Watermark

The **High Watermark (HW)** is the offset up to which all ISR members have
confirmed receipt. Consumers can only read records up to the HW — records above
it are not yet "committed" and could be rolled back.

```
Leader log:   [ 0 ][ 1 ][ 2 ][ 3 ][ 4 ]  ← log end offset = 4
ISR follower: [ 0 ][ 1 ][ 2 ]             ← replicated up to 2

High Watermark = 2  (min confirmed across ISR)
Consumer reads: 0, 1, 2  — cannot see 3, 4 yet
```

---

## Interview Angles

**Q: What does the Kafka Controller do?**
A: The Controller is a single elected broker responsible for cluster
administration: electing new partition leaders when a broker fails, tracking
broker join/leave events, handling topic CRUD operations, and propagating
updated metadata (partition→leader mapping) to all brokers. It is the control
plane; regular brokers handle the data plane.

**Q: Why did Kafka move away from ZooKeeper?**
A: ZooKeeper introduced operational burden (a second distributed system),
scalability limits (~200k partitions), and slow Controller failover (seconds).
KRaft stores metadata in a built-in Kafka Raft log (`__cluster_metadata`),
eliminating the external dependency, enabling millisecond failover, and scaling
to millions of partitions. ZooKeeper was removed entirely in Kafka 4.0.

**Q: How does KRaft Controller election work?**
A: A designated set of controller nodes form a Raft quorum. Raft elects a leader
among them via term-based voting. The Raft leader is the active Controller that
processes metadata changes. If the leader node fails, a follower wins a new
election in milliseconds — far faster than the ZooKeeper ephemeral-node race.

**Q: What is the ISR and why does it matter?**
A: The ISR is the set of partition replicas that have fetched within
`replica.lag.time.max.ms` of the leader. It matters because `acks=all` requires
all ISR members to confirm a write before the producer gets an ACK. A record
visible to consumers is guaranteed to be on every ISR member, so even if the
leader crashes the new leader (elected from ISR) has all committed data.

**Q: What is `min.insync.replicas` and how does it interact with `acks=all`?**
A: `min.insync.replicas` sets a floor on ISR size. With `acks=all`, if the
current ISR is smaller than this value, the broker returns
`NotEnoughReplicasException` instead of accepting the write. This prevents
losing durability silently when replicas lag. The standard recipe:
`replication.factor=3, min.insync.replicas=2, acks=all` — tolerates 1 broker
failure, rejects writes (rather than losing data) if 2 are down.

**Q: What is the High Watermark?**
A: The High Watermark is the largest offset that all ISR replicas have
confirmed. Consumers only read up to the HW — records above it are written on
the leader but not yet replicated to all ISR members and could be lost in a
crash. This guarantees consumers never see uncommitted data.

**Q: What is unclean leader election and when would you enable it?**
A: Unclean leader election allows a replica that is NOT in the ISR to become
leader. It risks data loss (the new leader may be missing records the old leader
acknowledged) but restores availability when all ISR members are unavailable.
Default is `false` (prefer safety). Enable `true` only for use-cases where
availability matters more than durability (e.g., metrics pipelines where losing
a few events is acceptable).
