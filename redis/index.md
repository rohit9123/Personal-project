# Redis Deep Dive — Zerodha SDE2 Prep

Deep-dive notes on Redis internals, built for fintech/exchange-grade backend interviews.
A broker like Zerodha leans on Redis for the **hot path**: live market-data ticks, session/token stores,
rate limiting on order APIs, idempotency, distributed locks, and caching in front of Postgres. So the bar
isn't "I've used `SET`/`GET`" — it's *why Redis is fast*, *where it loses data*, and *how you keep money
correct on top of a system that defaults to async replication and best-effort durability*.

Each note follows: **What → Why → How (internals) → Code/Example → Interview Angles**, same as the
[Postgres track](../postgres/index.md).

## Why these topics (the Zerodha angle)

Three themes run through everything an interviewer will push on:

1. **Latency & the single thread** — Redis is single-threaded for command execution. Understanding *why*
   that's fast, and why one `O(N)` command can stall every other client, is the #1 differentiator.
2. **Durability & failure** — Redis is an in-memory store with *async* replication and *configurable*
   persistence. "What exactly do you lose when the primary dies mid-trade?" is the money question.
3. **Atomicity without transactions-as-you-know-them** — no MVCC, no row locks. Correctness comes from
   the single thread + Lua/`MULTI`/`WATCH`. Distributed locks (Redlock) and idempotency live here.

## Curriculum

| # | Topic | File | Status |
|---|-------|------|--------|
| 1 | Data structures & internal encodings (string/hash/list/set/zset/stream, listpack/intset/skiplist) | 01-data-structures-internals.md | ✅ |
| 2 | Single-threaded model, event loop & pipelining (epoll, why O(N) is dangerous, Redis 6 threaded I/O) | 02-single-thread-event-loop.md | ✅ |
| 3 | Persistence: RDB, AOF, hybrid (fork/CoW, fsync policies, what you lose on crash) | 03-persistence.md | ✅ |
| 4 | Expiration & eviction (TTL, lazy vs active expiry, maxmemory policies, approximated LRU/LFU) | 04-expiration-eviction.md | ✅ |
| 5 | Caching patterns (cache-aside/write-through/write-behind, stampede, penetration/avalanche, TTL jitter) | 05-caching-patterns.md | ✅ |
| 6 | Atomicity: MULTI/EXEC/WATCH (CAS) & Lua scripting (why Lua beats MULTI) | 06-atomicity-transactions-lua.md | ✅ |
| 7 | Distributed locks: SET NX PX, lock expiry, Redlock & Kleppmann's critique, fencing tokens | 07-distributed-locks.md | ✅ |
| 8 | Pub/Sub vs Streams (consumer groups, at-least-once, Streams vs Kafka for market ticks) | 08-pubsub-streams.md | ✅ |
| 9 | Replication & HA: async replication, Sentinel, failover, the lost-write window | 09-replication-ha.md | ✅ |
| 10 | Redis Cluster & sharding (16384 hash slots, CRC16, MOVED/ASK, hash tags, multi-key limits) | 10-cluster-sharding.md | ✅ |
| 11 | Fintech patterns (rate limiting, idempotency, sessions, leaderboards, counters, dedup) | 11-fintech-patterns.md | ✅ |
| 12 | Memory model & ops (encodings, fragmentation, jemalloc, latency monitoring, slowlog, persistence ops) | 12-memory-ops.md | ✅ |
| 13 | **Postgres + Redis system design** (who owns which guarantee, cache-aside, idempotency, fencing, outbox, failure matrix) | [13-postgres-redis-system-design.md](13-postgres-redis-system-design.md) | ✅ |
| 14 | Specialized data types: HyperLogLog (cardinality), Bitmaps/Bitfields (DAU, packed counters), Geo (radius queries) | [14-specialized-data-types.md](14-specialized-data-types.md) | ✅ |
| 15 | Redis Pipelining vs. Lua Scripting (Network optimization vs. atomic server-side script execution) | [15-pipelining-vs-lua-scripting.md](15-pipelining-vs-lua-scripting.md) | ✅ |
| 16 | Redis Operations: Big Keys & Hot Keys (How they crash clusters, how to detect them, and design mitigations) | [16-hot-keys-big-keys.md](16-hot-keys-big-keys.md) | ✅ |
| 17 | Redis HyperLogLog Deep Dive (Mathematical foundation, bit-sliced 6-bit registers, sparse/dense encodings, Linear Counting) | [17-hyperloglog-deep-dive.md](17-hyperloglog-deep-dive.md) | ✅ |

## The 8 questions this track must let you answer cold

- **"Why is Redis fast even though it's single-threaded?"** → in-memory + O(1) structures + epoll event
  loop + no lock contention + no context switches. (Topic 2)
- **"You run `KEYS *` on production. What happens?"** → blocks the single thread, every other client
  stalls; use `SCAN`. (Topic 2)
- **"Primary crashes 50ms after `SET`. Is the write safe?"** → maybe not — async replication + AOF fsync
  policy decide. Walk the data-loss window. (Topics 3, 9)
- **"How do you cache the order book without a stampede at market open?"** → TTL jitter, single-flight /
  lock, stale-while-revalidate. (Topic 5)
- **"Implement a correct distributed lock."** → `SET key val NX PX`, unlock via Lua compare-and-del,
  then the honest caveat: it's not safe under GC pauses/failover without **fencing tokens** (Redlock
  critique). (Topic 7)
- **"Atomic 'decrement balance if ≥ amount'?"** → Lua script (single round trip, runs atomically on the
  one thread), or `WATCH`+`MULTI` CAS loop. (Topic 6)
- **"Live quotes to 1M clients — Pub/Sub, Streams, or Kafka?"** → trade-offs: Pub/Sub is fire-and-forget
  (a disconnect loses ticks); Streams persist + consumer groups; Kafka for durable replay at scale.
  (Topic 8)
- **"How does Redis Cluster route a key, and why can't you `MGET` across slots?"** → CRC16 mod 16384 →
  slot → node; multi-key ops require same slot (use hash tags). (Topic 10)

## Primary sources (depth Zerodha expects)

- **"Redis in Action" — Josiah Carlson** (free online) — patterns, locks, rate limiting, the practical bible.
- **Official Redis docs** — especially the *data types*, *persistence*, *replication*, *cluster spec*,
  and *Redlock* pages. The cluster spec and Redlock page are primary, not blog hearsay.
- **Martin Kleppmann — "How to do distributed locking"** — the canonical Redlock critique; **DDIA Ch. 8–9**
  for the distributed-systems framing (clocks, fencing tokens, partial failure).
- **Redis source `src/t_zset.c`, `src/dict.c`, `src/ae.c`** — for those who want the encodings and event
  loop at the byte level.
- **antirez's blog** (Redis creator) — design rationale for single-thread, Streams, and the Redlock debate.

→ **Start:** [01 — Data Structures & Internal Encodings](01-data-structures-internals.md)
→ **Capstone:** [13 — Postgres + Redis System Design](13-postgres-redis-system-design.md) — ties this track to the [Postgres track](../postgres/index.md) for the system-design round.
