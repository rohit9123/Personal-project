# PostgreSQL Deep Dive — Zerodha SDE2 Prep

Deep-dive notes on PostgreSQL internals, built for fintech/exchange-grade backend interviews.
The JD explicitly calls out **indexing, locking strategies, and transaction isolation levels** —
this track goes to implementation depth, not just textbook definitions.

Each note follows: **What → Why → How (internals) → Code/SQL Examples → Interview Angles**.

## Curriculum

| # | Topic | File | Status |
|---|-------|------|--------|
| 1 | Storage & MVCC internals (tuples, xmin/xmax, pages, HOT, WAL) | [01-mvcc-and-storage-internals.md](01-mvcc-and-storage-internals.md) | ✅ |
| 2 | Transaction isolation levels (RC / RR / Serializable + SSI) | [02-isolation-levels.md](02-isolation-levels.md) | ✅ |
| 3 | Locking strategies (row locks, FOR UPDATE/SHARE, advisory, deadlocks, SKIP LOCKED) | [03-locking.md](03-locking.md) | ✅ |
| 4 | Indexing deep dive (B-tree, partial, covering, GIN/GiST/BRIN, index-only scans) | [04-indexing.md](04-indexing.md) | ✅ |
| 5 | Query planning & EXPLAIN ANALYZE (cost model, statistics, join algorithms) | [05-query-planning.md](05-query-planning.md) | ✅ |
| 6 | Vacuum, bloat & txid wraparound (dead tuples, autovacuum tuning, wraparound emergency) | [06-vacuum-bloat.md](06-vacuum-bloat.md) | ✅ |
| 7 | WAL, checkpoints & replication (durability, recovery, sync/async, RPO/RTO, PITR) | [07-wal-replication.md](07-wal-replication.md) | ✅ |
| 8 | Connection pooling & performance at scale (process model, PgBouncer, work_mem, sharding) | [08-pooling-scale.md](08-pooling-scale.md) | ✅ |
| 9 | Fintech patterns (double-entry ledger, idempotency keys, exactly-once, outbox, reconciliation) | [09-fintech-patterns.md](09-fintech-patterns.md) | ✅ |
| 10 | JSONB: storage internals, operators (`@>`, `->>`, JSONPath), GIN vs expression indexing, when JSONB vs columns | [10-jsonb.md](10-jsonb.md) | ✅ |

## Primary sources (depth Zerodha expects)

- **"PostgreSQL 14 Internals" — Egor Rogov** (free PDF) — the implementation bible.
- **Official docs Ch. 13** — Concurrency Control (MVCC, isolation, locking).
- **Hussein Nasser** (YouTube) — byte-level storage & index intuition.
- **"Jordan has no life"** (YouTube) — *use for distributed-systems & isolation-theory mental model* (strong),
  not for Postgres implementation specifics (thin).
- **DDIA (Designing Data-Intensive Applications)** — Ch. 7 transactions, Ch. 3 storage.
