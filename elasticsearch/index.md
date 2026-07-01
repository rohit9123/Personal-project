# Elasticsearch Deep Dive — Zerodha SDE2 Prep

Deep-dive notes on Elasticsearch internals, built for fintech/exchange-grade backend interviews.
A broker like Zerodha doesn't use Elasticsearch as a system of record — Postgres owns the money. ES owns the
**search and analytics hot paths**: full-text search over instruments/orders/support tickets, log &
observability pipelines (the ELK stack on top of every service), audit-trail search over billions of events,
and near-real-time dashboards/aggregations that would crush an OLTP database. So the bar isn't "I can write a
`match` query" — it's *why an inverted index beats a B-tree for search*, *what you trade away in consistency*,
and *how you keep ES in sync with the source-of-truth database without corrupting it*.

Each note follows: **What → Why → How (internals) → Code/Example → Interview Angles**, same as the
[Postgres track](../postgres/index.md) and [Redis track](../redis/index.md).

## Why these topics (the Zerodha angle)

Three themes run through everything an interviewer will push on:

1. **Inverted index vs B-tree** — ES is fast at full-text and ad-hoc filtering because Lucene stores
   *term → document* postings, not *row → value*. Understanding *why* that wins for search and *why it loses*
   as a primary store is the #1 differentiator.
2. **Near-real-time, not real-time; AP, not ACID** — segments are immutable, writes are visible only after a
   **refresh** (~1s), and ES favors availability over strict consistency. "Is the write searchable
   immediately? Is it durable on a node crash?" is the money question (refresh vs flush vs translog).
3. **It's a secondary index, never the source of truth** — money lives in Postgres; ES is a derived,
   rebuildable search/analytics view. "How do you sync them, and what do you do when they diverge?" is the
   system-design round.

## Curriculum

| # | Topic | File | Status |
|---|-------|------|--------|
| 1 | Core architecture & data model (cluster, node roles, index, shard, replica, document, mapping vs row) | [01-architecture-data-model.md](01-architecture-data-model.md) | ✅ |
| 2 | Inverted index & Lucene storage internals (segments, immutability, postings, doc values, the `.fdt`/`.tim` files) | [02-inverted-index-lucene.md](02-inverted-index-lucene.md) | ✅ |
| 3 | The write path: refresh, flush, translog & segment merging (near-real-time + durability) | [03-write-path-refresh-flush-merge.md](03-write-path-refresh-flush-merge.md) | ✅ |
| 4 | Text analysis (analyzers, tokenizers, char/token filters, `text` vs `keyword`, analyze-time vs query-time) | [04-analysis-analyzers.md](04-analysis-analyzers.md) | ✅ |
| 5 | Mapping & field types (dynamic vs explicit mapping, multi-fields, `nested` vs `object`, mapping explosion) | [05-mapping-field-types.md](05-mapping-field-types.md) | ✅ |
| 6 | Query DSL (query vs **filter** context, `bool`, `match`/`term`/`range`, why filters are cached & fast) | [06-query-dsl.md](06-query-dsl.md) | ✅ |
| 7 | Relevance & scoring (TF-IDF → **BM25**, boosting, `function_score`, why scores aren't comparable across queries) | [07-relevance-bm25.md](07-relevance-bm25.md) | ✅ |
| 8 | Aggregations (bucket/metric/pipeline, `cardinality` ≈ HLL, the analytics engine, `doc_values` vs fielddata) | [08-aggregations.md](08-aggregations.md) | ✅ |
| 9 | Distributed search: sharding, routing, replication, the scatter-gather read path, `_routing` | [09-sharding-routing-search.md](09-sharding-routing-search.md) | ✅ |
| 10 | Cluster coordination & HA (master election, quorum, split-brain prevention, shard allocation, `green/yellow/red`) | [10-cluster-coordination-ha.md](10-cluster-coordination-ha.md) | ✅ |
| 11 | Performance & scaling at fintech scale (shard sizing, hot-warm-cold + ILM, bulk indexing, search/caching tuning) | [11-performance-scaling-ilm.md](11-performance-scaling-ilm.md) | ✅ |
| 12 | **Postgres + Elasticsearch system design** (CDC/dual-write sync, when NOT to use ES, search architecture, divergence) | [12-postgres-es-system-design.md](12-postgres-es-system-design.md) | ✅ |
| ⚡ | **One-page cheatsheet** — high-yield distillation of all 12 chapters; ~10-min last-minute review | [cheatsheet.md](cheatsheet.md) | ✅ |

## The 8 questions this track must let you answer cold

- **"Why is full-text search fast in Elasticsearch but slow in Postgres `LIKE '%x%'`?"** → inverted index
  (term → postings list) vs sequential scan / B-tree that can't seek on a leading wildcard. (Topics 2, 6)
- **"I index a document — is it immediately searchable? Is it durable?"** → searchable only after the next
  **refresh** (~1s, NRT); durable once written to the **translog** (per-request or every 5s); persisted to a
  segment on **flush**. Walk refresh vs flush vs translog. (Topic 3)
- **"`text` vs `keyword` — when each?"** → `text` is analyzed (tokenized, lowercased) for full-text `match`;
  `keyword` is stored verbatim for exact `term`, sorting, and aggregations. Use a multi-field for both. (Topics 4, 5)
- **"query vs filter context — what's the difference and why care?"** → query context computes a relevance
  `_score` (costly, uncacheable); filter context is yes/no, **cached**, and far faster. Put exact predicates
  in `filter`. (Topic 6)
- **"How does relevance scoring work?"** → BM25: term frequency (saturating) × inverse document frequency ×
  field-length normalization. Scores are per-query and **not comparable across queries**. (Topic 7)
- **"How does a search hit the right data across shards?"** → the doc is routed by `hash(_routing) % primary_shards`
  on write; a search **scatters** to one copy of every shard and **gathers**/merges the top-K. Shard count is
  fixed at index creation. (Topic 9)
- **"What's split-brain and how does ES prevent it?"** → two masters after a partition → divergent state;
  prevented by a quorum-based election (modern ES uses a Raft-like voting config; legacy used
  `minimum_master_nodes`). (Topic 10)
- **"Can ES be your primary database for orders/ledger?"** → no — it's near-real-time, AP, has no
  multi-document ACID transactions, and is a rebuildable secondary index. Postgres owns truth; ES is the
  derived search/analytics view, kept in sync via CDC/outbox. (Topics 3, 12)

## Primary sources (depth Zerodha expects)

- **"Elasticsearch: The Definitive Guide" — Gormley & Tong** (free online) — dated on APIs but the *concepts*
  (inverted index, relevance, distributed model) are still the best explanation anywhere.
- **Official Elasticsearch docs** — especially *mapping*, *analysis*, *query DSL*, *aggregations*, and the
  *"how documents/searches work in a distributed system"* and *"near real-time search"* pages.
- **Lucene in Action** / Lucene docs — for those who want the segment/postings/doc-values layer at the file level.
- **Elastic blog** — "BM25 vs TF-IDF", "Every shard deserves a home", and the cluster-coordination redesign posts.
- **DDIA (Designing Data-Intensive Applications)** — Ch. 3 (storage & LSM/segment intuition), Ch. 5–6
  (replication & partitioning) for the distributed framing.

## Related notes already in this repo

- [`es_vs_postgres_deep_dive.md`](../es_vs_postgres_deep_dive.md) — the Postgres-vs-ES architecture &
  Q&A comparison (feeds directly into Topic 12).
- [Postgres track](../postgres/index.md) — the system of record ES sits in front of.
- [Redis track](../redis/index.md) — the other hot-path store; note `cardinality` agg ≈ Redis HyperLogLog.

→ **Start:** 01 — Core Architecture & Data Model
→ **Capstone:** 12 — Postgres + Elasticsearch System Design — ties this track to the Postgres track for the system-design round.
