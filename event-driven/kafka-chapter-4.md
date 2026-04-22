# Kafka — Chapter 4: Producer Write Flow, Consumer Read Flow, Log Compaction & Why Kafka is Fast

Topics covered: Complete Producer Write Flow · Complete Consumer Read Flow · Log Compaction Strategies · Why Kafka is Fast

---

## 1. Complete Producer Write Flow

### Overview

```
Producer App
  │
  ▼
① Serialize key + value  (Serializer)
  │
  ▼
② Determine partition    (Partitioner)
  │
  ▼
③ Accumulate into batch  (RecordAccumulator — one deque per TopicPartition)
  │
  ▼
④ Sender thread drains ready batches → network I/O thread
  │
  ▼
⑤ Broker Leader receives ProduceRequest
  │
  ├─ writes record to local segment log (append-only)
  │
  ├─ followers fetch & replicate (ISR members)
  │
  └─ once all ISR confirm → High Watermark advances
  │
  ▼
⑥ Broker sends ACK back to producer (based on acks config)
  │
  ▼
⑦ Producer Callback fires (onCompletion) — success or retry
```

### Step-by-Step Detail

**① Serialization**
Key and value are converted to `byte[]` by the configured
`key.serializer` / `value.serializer` (e.g., `StringSerializer`,
`JsonSerializer`). The schema must match what the consumer expects.

**② Partitioning**
The Partitioner decides which partition the record lands on:
- **Key present** — `murmur2(key) % numPartitions`. Same key always goes to the
  same partition, guaranteeing ordering per key.
- **Key null** — default **StickyPartitioner** (Kafka 2.4+): fills a batch for
  one partition until `batch.size` or `linger.ms` is reached, then switches.
  Reduces the number of small requests compared to pure round-robin.
- **Custom** — implement `Partitioner` interface.

**③ RecordAccumulator — Batching**
Each `TopicPartition` has a `Deque<ProducerBatch>`. New records are appended to
the last open batch. A batch is "ready to send" when either:
- it is full (`batch.size`, default 16 KB), or
- `linger.ms` has elapsed (default 0 ms — send immediately; increase to 5–10 ms
  for better throughput at the cost of a small latency increase).

Batching is the primary throughput lever — it amortises per-request overhead
and enables compression across many records.

**④ Sender Thread**
A background `Sender` thread (separate from the application thread) drains ready
batches, groups them by broker (one `ProduceRequest` per broker per batch of
partitions), and hands them to the `NetworkClient`.

**⑤ Leader Broker Receives ProduceRequest**
1. Leader validates CRC, checks topic/partition existence and quota.
2. Appends record batch to the active **segment file** (sequential write).
3. Updates the in-memory offset index.
4. ISR followers issue Fetch requests and replicate the records.
5. Once all ISR members have the record, the partition's **High Watermark** advances.

**⑥ ACK Back to Producer**

| `acks` | Meaning | Risk |
|--------|---------|------|
| `0` | Fire-and-forget — no ACK waited | Data loss if broker crashes |
| `1` | ACK after leader write only | Data loss if leader crashes before replication |
| `all` / `-1` | ACK after all ISR members confirm | No data loss (safest) |

**⑦ Retry and Idempotence**
On network failure or retriable errors (`LEADER_NOT_AVAILABLE`), the producer
retries up to `retries` times with exponential backoff. With
`enable.idempotence=true`, each producer gets a PID and each batch a sequence
number — the broker deduplicates retries, guaranteeing **exactly-once** delivery
to the log.

---

## 2. Complete Consumer Read Flow

### Overview

```
Consumer App
  │
  ▼
① poll(Duration timeout)
  │
  ▼
② GroupCoordinator assigns partitions (if new group / rebalance)
  │
  ▼
③ FetchRequest → partition leader broker
  │
  ▼
④ Broker checks offset < High Watermark
  │
  ▼
⑤ Broker reads from page cache (segment file via OS)
  │  (zero-copy sendfile — no userspace copy)
  ▼
⑥ FetchResponse → consumer NetworkClient → ConsumerRecords returned to poll()
  │
  ▼
⑦ Application processes records
  │
  ▼
⑧ Commit offset to __consumer_offsets
  │  (auto-commit every auto.commit.interval.ms  OR  manual commitSync/commitAsync)
  ▼
⑨ On restart: read committed offset → resume from there
```

### Step-by-Step Detail

**① poll()**
`poll()` is the only way to consume. It drives the entire consumer lifecycle:
fetching records, heartbeating to the GroupCoordinator, triggering rebalances,
and running auto-commit. Calling it in a tight loop keeps the consumer alive;
not calling it within `max.poll.interval.ms` (default 5 min) causes the broker
to treat the consumer as dead and triggers a rebalance.

**② Partition Assignment**
On first `subscribe()` or after a rebalance, the **GroupCoordinator** (a broker)
uses the configured `partition.assignment.strategy`:
- `RangeAssignor` (default) — contiguous range per consumer.
- `RoundRobinAssignor` — round-robin across all partitions.
- `CooperativeStickyAssignor` (recommended) — minimises reassignments;
  supports incremental rebalances (consumer doesn't stop consuming unaffected
  partitions during rebalance).

**③ FetchRequest**
Consumer sends a `FetchRequest` to the **partition leader** specifying:
- `fetch.min.bytes` (default 1 B) — broker waits until at least this many bytes
  are available. Increasing reduces idle wakeups.
- `fetch.max.wait.ms` (default 500 ms) — max wait if data < `fetch.min.bytes`.
- `max.partition.fetch.bytes` (default 1 MB) — max bytes per partition per fetch.

**④ High Watermark Check**
The broker only returns records up to the **High Watermark** (the offset
confirmed by all ISR members). Records above the HW are on the leader but not
yet replicated — returning them would risk the consumer seeing data that gets
rolled back if the leader crashes.

**⑤ Zero-Copy Read (sendfile)**
Broker reads the segment file using the OS **page cache** and transfers data
directly to the network socket via the `sendfile()` syscall — no copy into JVM
heap. If the data is hot (recently produced), it is already in page cache and no
disk I/O occurs at all.

**⑥ Records Returned**
`poll()` returns a `ConsumerRecords<K, V>` containing batches from potentially
multiple partitions. Each record has: topic, partition, offset, key, value,
headers, timestamp.

**⑦ Processing**
Application processes records. Keep processing fast relative to
`max.poll.interval.ms` — if processing is slow, increase `max.poll.interval.ms`
or reduce `max.poll.records` (default 500) to fetch fewer records per poll.

**⑧ Offset Commit**
- **Auto-commit** (`enable.auto.commit=true`): committed at most every
  `auto.commit.interval.ms`. Risk: records processed but not yet committed are
  re-processed after crash (at-least-once). Records committed but not yet
  processed are skipped (at-most-once) if the commit fires at the wrong moment.
- **Manual sync** (`commitSync()`): blocks until broker confirms. Guarantees
  at-least-once if called after processing.
- **Manual async** (`commitAsync()`): non-blocking; use `commitSync()` on
  shutdown to flush the last offset.

**⑨ Restart Recovery**
On restart, the consumer reads the last committed offset from
`__consumer_offsets` and resumes from the next record. If no committed offset
exists, `auto.offset.reset` controls behaviour: `earliest` (read from
beginning), `latest` (skip to new records), `none` (throw exception).

---

## 3. Log Compaction

### What

Kafka supports two **log cleanup strategies** per topic, set via
`cleanup.policy`:

| Strategy | Behaviour |
|----------|-----------|
| `delete` (default) | Delete segments older than `retention.ms` (default 7 days) or larger than `retention.bytes`. Entire old segments are dropped. |
| `compact` | Keep only the **latest record for each key**. Older records with the same key are removed. The log shrinks but every key's current value is always retained. |
| `delete,compact` | Both policies apply — compact first, then apply time/size retention on top. |

### How Compaction Works

```
Before compaction:
offset:  0   1   2   3   4   5   6
key:     A   B   A   C   B   A   C
value:   a1  b1  a2  c1  b2  a3  c2

After compaction (keep latest per key):
offset:  3*  5   6        (* offset gaps are possible — holes are valid)
key:     C   A   C  ← NO, wrong example. Let me redo:

Latest per key: A→offset5(a3), B→offset4(b2), C→offset6(c2)

After compaction:
offset:  4   5   6
key:     B   A   C
value:   b2  a3  c2
```

The log is split into two regions:
- **Clean segment** — already compacted; each key appears at most once.
- **Dirty (tail) segment** — recently written; may have duplicate keys.

A background **Log Cleaner thread** periodically:
1. Scans the dirty portion and builds an **offset map** (`key → latest offset`).
2. Rewrites dirty segments, dropping any record whose key has a higher-offset
   version.
3. Merges the rewritten segments into the clean portion.

### Tombstones

A record with `value = null` is a **tombstone** — it signals "delete this key".
After compaction, the tombstone is retained for `delete.retention.ms` (default
24 h) so consumers that were behind can see the delete, then it is removed.

### Use Cases for Compaction

| Use Case | Why compaction |
|----------|---------------|
| **CDC (Change Data Capture)** | Topic holds the current state of each DB row; old row versions are irrelevant. |
| **Kafka Streams state store changelogs** | Restoring state stores from a compacted topic is much faster than replaying the full history. |
| **Configuration/feature-flag topics** | Only the latest value of each config key matters. |
| **User profile / session topics** | Current profile per user ID, not full update history. |

### Key Config

```properties
cleanup.policy=compact
min.cleanable.dirty.ratio=0.5   # compact when dirty/total log > 50%
delete.retention.ms=86400000    # tombstones survive 24 h before removal
segment.ms=604800000            # roll a new segment every 7 days (ensures cleaner can act)
```

---

## 4. Why Kafka is Fast

Kafka achieves throughput of millions of messages per second on commodity
hardware through five design decisions, not one magic trick:

### ① Sequential Disk I/O

Kafka **appends** records to the end of segment files — it never updates or
seeks within a file. Sequential writes on a spinning disk can reach 600 MB/s;
sequential reads are similar. Random I/O on the same disk is ~100× slower.
SSDs close the random/sequential gap but sequential writes still win on write
amplification.

Most message queues maintain complex in-memory data structures and do random
writes. Kafka's log is a dumb append-only file — simpler and faster.

### ② OS Page Cache Instead of JVM Heap

Kafka does **not** manage its own cache. It writes to the OS page cache and
relies on the OS to keep hot data in memory. Benefits:
- **No JVM GC pressure** — multi-GB caches in a JVM cause long GC pauses;
  page cache is outside the JVM.
- **Warm reads are free** — if a consumer is close to the producer (common case)
  data is read directly from page cache with no disk I/O at all.
- **Cache survives broker restart** — the page cache is OS-managed; a JVM
  in-memory cache is lost on restart.

### ③ Zero-Copy Transfer (`sendfile`)

Normal network read path (without zero-copy):
```
disk → kernel buffer → user buffer (JVM) → kernel socket buffer → NIC
         (2 copies + 2 context switches in user space)
```

Kafka uses the `sendfile()` syscall (Linux) / `transferTo()` (Java NIO):
```
disk → kernel buffer ────────────────────→ NIC
         (1 copy, stays in kernel — no user space involvement)
```

For a consumer reading records that are already in page cache this means: no
disk I/O + no JVM heap copy + one kernel copy. This is the dominant factor for
consumer throughput.

### ④ Batching and Compression

- **Producer batching**: `RecordAccumulator` groups records into batches
  (`batch.size`, `linger.ms`). One network round-trip carries thousands of
  records instead of one.
- **Broker-to-broker replication**: followers fetch in large batches.
- **Consumer fetches**: `fetch.min.bytes` / `fetch.max.wait.ms` ensure the
  broker responds with full batches, not one record at a time.
- **Compression**: `compression.type` (snappy, lz4, gzip, zstd) is applied at
  the batch level. Compressing many records together gives much better ratios
  than per-record compression. The broker stores the compressed batch as-is and
  the consumer decompresses — the broker does zero extra work for compression.

### ⑤ Partition Parallelism

A topic is sharded into partitions, each an independent log on a potentially
different broker. This means:
- Producers write to multiple partitions in parallel.
- Each partition can be consumed by a different consumer instance simultaneously.
- Replication is per-partition and distributed across brokers.

Throughput scales horizontally: doubling partitions (and brokers) roughly
doubles throughput.

### Summary Table

| Technique | What it avoids | Gain |
|-----------|---------------|------|
| Sequential I/O | Random seek latency | ~100× faster disk writes |
| Page cache | JVM GC pauses, cache rebuild on restart | Free warm reads |
| Zero-copy (`sendfile`) | User-space copy, context switches | Near-wire-speed consumer throughput |
| Batching | Per-record network overhead | Orders-of-magnitude throughput multiplier |
| Compression | Network bandwidth | Reduced latency + cost |
| Partition parallelism | Single-threaded bottleneck | Linear horizontal scale |

---

## Interview Angles

**Q: Walk me through what happens when a producer calls `send()`.**
A: The record is serialized, the Partitioner picks a partition (key hash or
sticky), and the record is appended to the `RecordAccumulator` batch for that
`TopicPartition`. The background Sender thread drains ready batches (when
`batch.size` is full or `linger.ms` elapses) and sends a `ProduceRequest` to
the partition leader. The leader appends to its segment log, followers fetch and
replicate, the High Watermark advances, and the broker ACKs back. The producer's
callback fires with the assigned offset or an error triggering retry.

**Q: Walk me through what happens when a consumer calls `poll()`.**
A: `poll()` drives heartbeating and rebalance detection. Internally it issues a
`FetchRequest` to each partition's leader, specifying the current offset and
fetch size limits. The broker reads records up to the High Watermark from the
page cache using zero-copy `sendfile`. `ConsumerRecords` are returned. After
processing, the consumer commits offsets to `__consumer_offsets` (auto or
manual). On restart the consumer reads that committed offset and resumes from
the next record.

**Q: What is the difference between `delete` and `compact` cleanup policies?**
A: `delete` removes entire old segments once their age exceeds `retention.ms` or
total size exceeds `retention.bytes` — good for time-series event streams where
old events have no value. `compact` retains only the latest record per key
indefinitely — good for current-state topics (CDC, config, user profiles) where
you need to rebuild state from the topic but don't need the full history.

**Q: What is a tombstone in a compacted topic?**
A: A record with `value=null`. It tells downstream consumers "this key has been
deleted." After `delete.retention.ms` the tombstone itself is removed during the
next compaction cycle. Consumers lagging behind the tombstone deletion window
will miss the delete signal — this is why `delete.retention.ms` should be longer
than the expected max consumer lag.

**Q: Why is Kafka faster than traditional message brokers?**
A: Five reasons: (1) append-only sequential disk writes avoid seek latency;
(2) relying on OS page cache avoids JVM GC pauses and keeps hot data warm for
free; (3) zero-copy `sendfile` sends data from page cache to network without a
user-space copy; (4) batching amortises per-record overhead over thousands of
records per request; (5) partition-level parallelism scales horizontally across
brokers and consumers. Most traditional brokers maintain complex in-memory
structures, do random I/O, and copy data through multiple buffers.

**Q: What does zero-copy mean in Kafka's context?**
A: Zero-copy means the data path from disk to network socket avoids copying
bytes into the JVM heap. Kafka uses the Linux `sendfile()` syscall (exposed as
`FileChannel.transferTo()` in Java NIO): the OS transfers data from the kernel's
page cache buffer directly to the NIC's buffer in one step, bypassing user space.
The result is no GC allocation, one kernel copy instead of four (disk→kernel
buffer → user buffer → kernel socket buffer → NIC), and fewer context switches.

**Q: What is the role of `linger.ms` and `batch.size` in producer throughput?**
A: `batch.size` sets the maximum bytes per batch per partition — when a batch
fills up it is sent immediately. `linger.ms` adds an artificial delay: even if
the batch is not full, the sender waits up to `linger.ms` for more records to
arrive before sending. Setting `linger.ms=5` lets 5 ms of records accumulate,
producing larger batches. The trade-off: slightly higher latency (5 ms worst
case) for much higher throughput (fewer, fuller requests). The default is 0 ms
(lowest latency, suboptimal throughput).
