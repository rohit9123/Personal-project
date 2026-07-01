

# Kafka — Chapter 6: Failure Scenarios

Topics covered: Broker Fail · Partition Leader Fail · Follower Out of ISR · Controller Fail · Consumer Crash · Consumer Timeout · Group Coordinator Fail · Transaction Coordinator Fail / Zombie Producer · Graceful Shutdown · Disk/Network Fail · Schema Registry · Multi-DC Fail · Cooperative Rebalancing

---

## 1. Broker Fails

### What
A broker process dies (crash, OOM, machine reboot). It stops serving produce/consume requests and stops sending heartbeats to the Controller.

### How
```mermaid
flowchart TD
    A["Broker-2 crashes"] --> B["1. Controller detects missed heartbeat\n(ZK session expiry or KRaft heartbeat timeout)"]
    B --> C["2. Controller identifies all partitions\nwhere Broker-2 was the leader"]
    C --> D["3. For each partition: elect new leader\nfrom ISR (e.g. Broker-3)"]
    D --> E["4. Controller writes new leader assignments\nto __cluster_metadata (KRaft) or ZK znodes,\nthen pushes LeaderAndIsr request to all brokers"]
    E --> F["5. Producers / consumers get\nLEADER_NOT_AVAILABLE on next request"]
    F --> G["6. Clients call Metadata API\n→ get new leader → resume normally"]
```

### Key Config

| Config | Default | Role |
|--------|---------|------|
| `replica.lag.time.max.ms` | 30 000 ms | Follower must fetch within this window to stay in ISR |
| `zookeeper.session.timeout.ms` | 18 000 ms | (ZK mode) time before ZK declares broker dead |
| `broker.heartbeat.interval.ms` | 2 000 ms | (KRaft) how often brokers send heartbeat |
| `broker.session.timeout.ms` | 9 000 ms | (KRaft) time before Controller declares broker dead |

### Impact Matrix

| Component | Effect on broker fail |
|-----------|----------------------|
| Producers | `LEADER_NOT_AVAILABLE` error → retry + metadata refresh |
| Consumers | Partition reassigned to new leader; existing offset commit works as normal |
| Followers on dead broker | Removed from ISR; rejoins when broker restarts and catches up |
| `min.insync.replicas` | If ISR drops below threshold, writes to those partitions fail with `NotEnoughReplicasException` |

---

## 2. Partition Leader Fails

### What
The specific broker that is the leader for a partition crashes. Producers writing to that partition and consumers reading from it are affected.

### How
```mermaid
flowchart TD
    A["Partition 0: Leader=Broker-1\nISR=[Broker-1, Broker-2, Broker-3]\nHW = offset 100"] --> B["Broker-1 crashes"]
    B --> C["Controller elects Broker-2 as new leader"]
    C --> D["New leader already has all\ncommitted offsets ≤ HW=100"]
    C --> E["Broker-3 (old follower)\nnow fetches from Broker-2"]
    D --> F["Producers reconnect to Broker-2,\nresume from offset 101"]
    E --> F
```

### The Unclean Election Trade-off

If the leader dies and **all ISR members are also unavailable**, Kafka must choose:

```mermaid
flowchart TD
    A["ISR=[Broker-1] → Broker-1 also crashes"] --> B{"unclean.leader.election.enable?"}
    B -->|"false (default, safe)"| C["Partition is OFFLINE until\nan ISR member comes back"]
    C --> C1["No data loss but unavailable\n(writes fail)"]
    B -->|"true (available, risky)"| D["Elect Broker-3\n(was NOT in ISR — lagged behind)"]
    D --> D1["Broker-3 may be missing offsets 95-100\nThose messages are permanently lost"]
    D1 --> D2["Consumers may see gaps or re-reads"]
```

**Rule**: for financial / critical data keep `unclean.leader.election.enable=false`. For log / metrics pipelines where availability > durability, `true` is acceptable.

### High Watermark and Data Safety

```mermaid
flowchart TD
    subgraph Before["Before Crash"]
        L["Leader (Broker-1)\nlog ends at offset 105\nproducer wrote 5 extra, not replicated"]
        F["Follower (Broker-2)\nlog ends at offset 100\nonly this is committed (HW=100)"]
    end
    Before --> Crash["Broker-1 crashes\nBroker-2 becomes leader"]
    subgraph After["After Failover"]
        A1["Broker-2 truncates nothing — it IS the HW"]
        A2["Offsets 101-105 from Broker-1 are gone\n(were never committed)"]
        A3["Producer retries → offsets 101-105\nare rewritten starting from 101"]
    end
    Crash --> After
```

This is why `acks=all` matters: the producer only gets an ACK after all ISR members have the record, so if the leader dies the new leader definitely has it.

---

## 3. Follower Replica Falls Out of ISR

### What
A follower broker becomes slow (GC pause, network lag, disk I/O spike) and stops fetching fast enough. The Controller evicts it from the ISR without killing the broker.

### How
```mermaid
flowchart TD
    A["Broker-3 (follower) doesn't send a Fetch\nto Broker-1 (leader) for 30+ seconds\n(replica.lag.time.max.ms = 30 000 ms)"] --> B["Controller removes Broker-3 from ISR\nISR: [B1, B2, B3] → [B1, B2]"]
    B --> C["Writes continue\n(ISR still satisfies min.insync.replicas=2)"]
    C --> D["Broker-3 recovers, resumes fetching,\ncatches up to HW"]
    D --> E["Controller re-adds Broker-3 to ISR\nISR: [Broker-1, Broker-2, Broker-3]"]
```

### Why This Matters

| Scenario | Effect |
|----------|--------|
| ISR shrinks to exactly `min.insync.replicas` | Cluster is fragile — one more failure stops writes |
| ISR shrinks **below** `min.insync.replicas` | All produce requests with `acks=all` fail with `NotEnoughReplicasException` |
| ISR shrinks to 1 (only leader) | Any leader crash will trigger unclean election dilemma |

Monitor the `UnderReplicatedPartitions` JMX metric — it should be 0 in steady state. Any non-zero value means your cluster is operating without full durability.

---

## 4. Controller Fails

### What
The single active Controller broker (or Controller node in KRaft) crashes. Without a Controller, no new partition leader elections can be triggered — existing leaders keep working, but no failover is possible until a new Controller is elected.

### ZooKeeper Mode
```mermaid
flowchart TD
    A["Controller = Broker-1\n(holds /controller znode in ZooKeeper)"] --> B["Broker-1 crashes"]
    B --> C["ZooKeeper session expires\n(zookeeper.session.timeout.ms ~ 18 s)"]
    C --> D["/controller znode deleted"]
    D --> E["Remaining brokers race\nto create /controller"]
    E --> F["First to succeed becomes\nnew Controller"]
    F --> G["New Controller reads all partition\nstate from ZooKeeper and resumes"]
    G --> H["Total failover: seconds\n(ZK timeout + race + state reload)"]
```

### KRaft Mode
```mermaid
flowchart TD
    A["Controller quorum:\nController-1 (active), Controller-2, Controller-3"] --> B["Controller-1 crashes"]
    B --> C["Followers detect missed heartbeat\n(broker.session.timeout.ms ~ 9 s)"]
    C --> D["Raft election: one follower\nwins majority vote"]
    D --> E["New active Controller resumes\nfrom __cluster_metadata log\n(no external system involved)"]
    E --> F["Total failover: milliseconds"]
```

### During the Gap (No Active Controller)
- Existing partition leaders keep serving produce/consume — **data plane is unaffected**
- No new leader elections can happen → a broker crash during this gap leaves its partitions offline until the new Controller is elected
- No new topics or partition changes can be made

### ZooKeeper vs KRaft Controller Comparison

| | ZooKeeper Mode | KRaft Mode |
|---|---|---|
| Election mechanism | ZK ephemeral znode race | Raft quorum vote |
| Failover time | Seconds | Milliseconds |
| Metadata store | ZooKeeper znodes | `__cluster_metadata` topic |
| Partition limit | ~200 k partitions | Millions |
| External dependency | Yes (ZooKeeper cluster) | No |
| Split-brain risk | Yes (ZK + broker state drift) | No (Raft linearizable log) |

---

## 5. Consumer Crashes

### What
A consumer process dies (OOM, kill signal, machine reboot). Its heartbeat thread stops. The GroupCoordinator eventually detects the missing heartbeat and triggers a rebalance.

### How
```mermaid
flowchart TD
    A["Consumer-C3 crashes"] --> B["GroupCoordinator waits\nsession.timeout.ms (default 45 s)"]
    B --> C["No heartbeat received\n→ C3 declared dead"]
    C --> D["Coordinator triggers rebalance"]
    D --> E["JoinGroup → all alive consumers rejoin"]
    E --> F["SyncGroup → leader consumer\nrecomputes assignment"]
    F --> G["C3's partitions redistributed\nto C1 and C2"]
    G --> H["C1/C2 resume from the last\ncommitted offset for C3's former partitions"]
```

### Offset Recovery

```mermaid
flowchart TD
    A["C3 was at offset 500 processing\ncommitted up to offset 490"] --> B["C3 crashes at offset 500\noffsets 491-500 were processed\nbut NOT committed"]
    B --> C["Rebalance: C1 takes over\nC3's partition"]
    C --> D["C1 reads committed offset = 490"]
    D --> E["C1 reprocesses offsets 491-500\n(at-least-once delivery)"]
```

This is why you must call `commitSync()` after processing, not before — and why idempotent consumers matter.

---

## 6. Consumer Timeout — Two Variants

### What
A consumer is alive but fails to interact with the GroupCoordinator in time. Two independent timers govern this:

| Timer | Config | Default | Detects |
|-------|--------|---------|---------|
| Heartbeat timeout | `session.timeout.ms` | 45 000 ms | Consumer crash / network dead (heartbeat thread stopped) |
| Poll timeout | `max.poll.interval.ms` | 300 000 ms | Consumer alive but stuck in processing (heartbeat runs, `poll()` does not) |

### session.timeout.ms (Crash Detection)
```mermaid
flowchart TD
    A["Consumer thread is dead /\nnetwork partitioned"] --> B["Heartbeat background thread also stops\n(or can't reach broker)"]
    B --> C["GroupCoordinator sees no heartbeat\nfor session.timeout.ms"]
    C --> D["Consumer declared dead\n→ rebalance triggered"]
```

### max.poll.interval.ms (Slow Processing Detection)
```mermaid
flowchart TD
    A["Consumer is alive\nProcessing one batch takes 6 minutes"] --> B["Heartbeat thread IS still running\n→ coordinator thinks consumer is healthy"]
    B --> C["poll() has not been called for\n> max.poll.interval.ms (5 min default)"]
    C --> D["Coordinator triggers rebalance\nremoves consumer from group"]
    D --> E["Consumer tries to commit offset\n→ CommitFailedException"]
    D --> F["Consumer tries to poll()\n→ reassigned partitions"]
```

### Fix for Slow Processing
```java
// Option 1: increase the poll interval
props.put("max.poll.interval.ms", "600000"); // 10 min

// Option 2: reduce records per poll so each batch finishes faster
props.put("max.poll.records", "50"); // default 500

// Option 3: process asynchronously and commit manually per record
```

### When Each Timer Matters

```mermaid
flowchart LR
    A{"Failure type?"} -->|"Consumer dies suddenly"| B["session.timeout.ms fires first"]
    A -->|"Consumer stuck in processing\n(heartbeat running but slow)"| C["max.poll.interval.ms fires first"]
    A -->|"Both timers exceeded"| D["Whichever is shorter fires"]
```

---

## 7. Group Coordinator Fails

### What
The GroupCoordinator is not a separate service — it is a **regular broker** that happens to be the leader of the `__consumer_offsets` partition for a given consumer group. If that broker dies, the coordinator for all groups it served becomes unavailable.

### How
```mermaid
flowchart TD
    A["GroupCoordinator = Broker-2\n(leader of __consumer_offsets partition 7)"] --> B["Broker-2 crashes"]
    B --> C["Controller elects new leader for\n__consumer_offsets partition 7 (e.g. Broker-3)"]
    C --> D["Broker-3 is now the GroupCoordinator\nfor all groups that hash to partition 7"]
    D --> E["Consumers discover new coordinator\nvia FindCoordinator API request"]
    E --> F["Normal operation resumes"]
    B -.->|"During the gap"| G["Offset commits fail\nNew consumers cannot join groups\nExisting consumers keep consuming\n(they buffer unconfirmed offsets in memory)"]
```

### Durability of __consumer_offsets
By default `__consumer_offsets` has:
- `replication.factor=3`
- `min.insync.replicas=2`

This means **2 brokers must fail simultaneously** before offset storage becomes unavailable. In practice, offset storage is highly resilient.

---

## 8. Transaction Coordinator Fails / Zombie Producer

### Transaction Coordinator Fail

The Transaction Coordinator is also a broker — the leader of the `__transaction_state` partition for a given `transactional.id`.

```mermaid
flowchart TD
    A["Transaction Coordinator = Broker-1\nProducer is mid-transaction:\nBeginTransaction → Produce"] --> B["Broker-1 crashes"]
    B --> C["New Transaction Coordinator elected (Broker-3)\nReads __transaction_state log\n→ sees transaction in ONGOING state"]
    C --> D{"Producer still alive?"}
    D -->|"Yes"| E["Producer retries EndTransaction\nBroker-3 picks up from logged state\n→ commits or aborts cleanly"]
    D -->|"No (producer also died)"| F["Transaction stays ONGOING until\ntransaction.timeout.ms elapses"]
    F --> G["New Coordinator writes ABORT marker\n→ partitions cleaned up"]
```

### Zombie Producer (Fencing)

A "zombie" is a producer that was replaced by a new instance (network partition, restart) but the old instance is still alive and trying to write.

```mermaid
flowchart TD
    A["Producer-A starts\ntransactional.id='order-producer'\nPID=10, epoch=0"] --> B["Network partition\nKafka thinks A is dead"]
    B --> C["Producer-B starts with same\ntransactional.id='order-producer'\nCoordinator increments epoch: PID=10, epoch=1"]
    C --> D["Network heals\nProducer-A (epoch=0) tries to\ncommit its transaction"]
    D --> E["Broker rejects:\nProducerFencedException\n'epoch 0 is stale; current epoch is 1'"]
    E --> F["Producer-A must stop\nProducer-B continues safely"]
```

This is the **fencing mechanism** that makes exactly-once semantics safe. Without it, both producers would write and the log would have duplicates.

### Zombie Summary

| Who | ID used | What prevents duplication |
|-----|---------|--------------------------|
| Idempotent producer | PID + Sequence Number | Broker deduplicates retries within a session |
| Transactional producer | `transactional.id` + Epoch | Epoch increment fences old instances across sessions |

---

## Failure Quick-Reference

| Failure | Detected by | Detection time | Recovery mechanism | Data loss risk |
|---------|-------------|----------------|--------------------|---------------|
| Broker crash | Controller (heartbeat) | KRaft: ms / ZK: ~18 s | New partition leader elected from ISR | No (if `acks=all` and ISR ≥ 2) |
| Partition leader crash | Same as broker | Same | ISR member promoted to leader | No (committed data safe) |
| Follower out of ISR | Controller (`replica.lag.time.max.ms`) | Up to 30 s | Evicted; re-added on catchup | No data loss; durability margin reduced |
| Controller crash | ZK / Raft quorum | ms–seconds | New Controller elected | No (data plane unaffected) |
| Consumer crash | GroupCoordinator (`session.timeout.ms`) | Up to 45 s | Rebalance; partitions reassigned | No (at-least-once reprocessing from committed offset) |
| Consumer slow / stuck | GroupCoordinator (`max.poll.interval.ms`) | Up to 5 min | Rebalance; `CommitFailedException` | No |
| Group Coordinator crash | Controller | ms–seconds | New broker elected leader for `__consumer_offsets` | No (RF=3 default) |
| Transaction Coordinator crash | Controller | ms–seconds | New broker takes over `__transaction_state` | No (open tx aborted after timeout) |
| Zombie producer | Epoch check on Coordinator | Immediate on request | `ProducerFencedException`; zombie must stop | No (fencing prevents double-write) |
| Disk Full / Error | OS / Kafka Log Layer | Immediate | Broker shutdown; replicas take over | No |
| Network Partition | Client Retries | `delivery.timeout.ms` | Client metadata refresh / bootstrap failover | No (if retries configured) |
| Schema Registry Down | Serializer/Deserializer | Immediate | HA Registry failover / Client-side cache | No (but processing halts) |
| DC Failure | Monitoring / Ops | Minutes | Failover to DR Cluster (MirrorMaker 2.0) | Minor (async replication lag) |

---

## Interview Angles

**Q: A broker crashes and one of its partitions has ISR=[that broker only]. What happens?**
A: The ISR is now empty. With `unclean.leader.election.enable=false` (default), the partition goes offline — no reads or writes — until the broker recovers. With `unclean.leader.election.enable=true`, an out-of-ISR replica is promoted but any messages the dead broker had that weren't replicated are permanently lost. The right answer depends on the durability vs availability trade-off for that topic.

**Q: What is the difference between `session.timeout.ms` and `max.poll.interval.ms`?**
A: `session.timeout.ms` detects physical failures — the consumer's heartbeat thread stops (crash, network partition). `max.poll.interval.ms` detects logical stalls — the consumer is alive but its processing is so slow that `poll()` isn't called in time. Both trigger a rebalance, but the second one is caused by slow application code, not infrastructure failure. Fix: reduce `max.poll.records` or increase `max.poll.interval.ms`.

**Q: How does Kafka prevent a zombie producer from corrupting the log?**
A: Every `transactional.id` is associated with a producer epoch. When a new producer instance registers with the same `transactional.id`, the Transaction Coordinator increments the epoch. Any subsequent request from the old instance (with the stale epoch) is rejected with `ProducerFencedException`. The old producer must stop — it cannot write or commit. This makes exactly-once semantics safe across producer restarts and network partitions.

**Q: What happens to in-flight transactions when the Transaction Coordinator broker dies?**
A: The Transaction Coordinator's state is stored in `__transaction_state`, which is a replicated Kafka topic. When the coordinator broker crashes, a new broker becomes leader for that partition and reads the transaction log. If the producer retries the commit, the new coordinator completes it. If the producer also died, the transaction remains ONGOING until `transaction.timeout.ms` elapses, at which point the coordinator writes an ABORT marker and cleans up.

**Q: What does `NotEnoughReplicasException` mean and how do you prevent it?**
A: It means the number of in-sync replicas for a partition has fallen below `min.insync.replicas`. Kafka refuses the write rather than risk losing data. This happens when brokers fall out of the ISR faster than they recover. Prevention: set `replication.factor=3` and `min.insync.replicas=2` — this tolerates 1 broker failure. Monitor `UnderReplicatedPartitions`; if it's non-zero you are one more failure away from this exception.

**Q: During a Controller election (after Controller crash), can consumers still read messages?**
A: Yes. The data plane (produce/consume) is handled by partition leader brokers, not the Controller. The Controller is only needed for metadata changes — electing new leaders, adding/removing brokers, creating topics. Existing partition leaders keep serving requests normally during the Controller election window. The only risk is if a broker crash happens simultaneously, in which case that partition will be unavailable until a new Controller is elected.

**Q: What is the risk of using `acks=1` during an ungraceful shutdown?**
A: With `acks=1`, the leader acknowledges as soon as it writes to its local log. If the broker crashes (ungraceful) before it can `fsync` to disk or replicate to followers, that data is permanently lost. Always use `acks=all` for critical data.

**Q: How does `group.instance.id` help with consumer failure scenarios?**
A: It enables **Static Group Membership**. If a consumer pod restarts (common in Kubernetes), the Group Coordinator recognizes the ID and waits for it to rejoin without triggering a rebalance. This prevents "rebalance storms" for short-lived consumer failures.

**Q: How do you handle a "Network Black Hole" where only the client is cut off?**
A: This is why `bootstrap.servers` should contain multiple brokers. If one network path is down, the client can reach other brokers to refresh metadata and discover a working path to the leader. On the producer side, `delivery.timeout.ms` must be set high enough to survive temporary blips.

---

## Appendix: Additional Scenarios

### 9. Graceful vs. Ungraceful Shutdown

| Feature | Graceful (`kill -15`) | Ungraceful (`kill -9` / Power loss) |
|---|---|---|
| **Mechanism** | Migrates leaders *before* stopping | Sudden halt; Controller must detect failure |
| **Downtime** | Near Zero | Window of unavailability (seconds) |
| **Log Recovery** | Instant on restart | Slow (rebuilds indexes from checkpoint) |

### 10. Disk Failures & Exhaustion
Kafka is "fail-fast." If a disk fills up or an I/O error occurs, the broker shuts down immediately.
*   **Impact:** Partitions on that broker go offline.
*   **Recovery:** Replace disk; Kafka re-replicates data from other ISR members.

### 11. External Dependency (Schema Registry)
If the Schema Registry fails, producers cannot register new schemas and consumers cannot fetch them to deserialize.
*   **Symptom:** "Schema not found" errors or infinite retries in serialization.
*   **Mitigation:** Highly Available (HA) Registry setup and client-side schema caching.

### 12. Multi-DC Disaster Recovery
Using **MirrorMaker 2.0 (MM2)** to replicate data between an Active DC and a Passive/Standby DC.
*   **Scenario:** Region-wide failure.
*   **Recovery:** Point clients to the Standby cluster. MM2 handles offset translation to ensure consumers resume near where they left off.
