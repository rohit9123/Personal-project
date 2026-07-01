# Elasticsearch — One-Page Cheatsheet

> Condensed from the 12-chapter deep dive. Everything high-yield for an interview, scannable in ~10 min.
> Read a full chapter only when you want the *why* behind a row.

---

## 🧠 The 5 things to never get wrong

1. **Inverted index, not B-tree** — Lucene stores `term → list of docs` (postings). That's why full-text + ad-hoc filtering is fast, and why ES is a *search engine*, not a system of record.
2. **Near-real-time, not real-time** — a write is durable immediately (translog) but **searchable only after a refresh (~1s)**.
3. **AP, not ACID** — favors availability; no multi-doc transactions. **Never the source of truth** — it's a rebuildable, derived view of your real DB (e.g. Postgres).
4. **Segments are immutable** — writes create new segments; deletes/updates just mark old docs as deleted; **merges** reclaim space later.
5. **Filter context is cached & scoreless; query context scores** — use `filter` for yes/no, `must` only when you need relevance.

---

## 🏗️ Vocabulary (map to a relational DB)

| Elasticsearch | Relational analogy | Note |
|---|---|---|
| Index | Table | A logical collection of documents |
| Shard | Partition | A **Lucene index**; unit of scale & parallelism |
| Replica | Read replica | Copy of a shard; HA + read throughput |
| Document | Row | A JSON object |
| Field + mapping | Column + schema | Mapping = how a field is indexed |
| `_id` | Primary key | Same id = overwrite (idempotent) |

- **Shards are fixed at index creation** (primaries). Replicas can change anytime.
- Total shards a query hits = **primaries × (1 + replicas)** worth of routing → keep shard count sane (aim ~10–50GB/shard).

---

## ✍️ Write path (refresh / flush / merge)

```
index doc → in-memory buffer + translog (durable)
   │
   ├─ refresh (default 1s) → buffer becomes a searchable segment (NOT fsynced)
   ├─ flush  (~30min/512MB) → segments fsynced to disk, translog cleared
   └─ merge  (background)   → many small segments → fewer big ones; purges deletes
```

| Term | Makes it… | Trigger |
|---|---|---|
| **refresh** | **searchable** | every `1s` (tunable; `-1` to disable during bulk loads) |
| **flush** | **durable on disk** (fsync) | size/time threshold |
| **translog** | **crash-safe before flush** | every write; fsync per request by default |

> 💡 Bulk-load tip: set `refresh_interval: -1` and `number_of_replicas: 0` during big imports, then restore. Huge speedup.

---

## 🔤 Analysis & field types (the #1 source of bugs)

| Type | Analyzed? | Use for | Sortable/aggregatable? |
|---|---|---|---|
| `text` | ✅ tokenized | full-text search (`match`) | ❌ (no doc_values) |
| `keyword` | ❌ exact | filters, sort, aggs, IDs, enums | ✅ |

- **Default dynamic mapping** maps strings as `text` **+** a `.keyword` sub-field → use `myField` for search, `myField.keyword` for exact/sort/aggs.
- **Analyzer** = char filters → tokenizer → token filters (e.g. lowercase, stop words, stemming). Applied at **index time** *and* **query time** (must match!).
- **`object` vs `nested`**: arrays of objects flatten and cross-match by default; use **`nested`** when you need fields within one array element to stay together (costs a hidden doc per element).
- **Mapping explosion**: too many dynamic fields kills the cluster. Use `strict` mappings or `flattened` type for arbitrary keys.

---

## 🔍 Query DSL essentials

```json
{ "query": { "bool": {
  "must":   [ { "match": { "title": "kafka consumer" } } ],   // scored, full-text
  "filter": [ { "term":  { "status.keyword": "ACTIVE" } },     // cached, scoreless
              { "range": { "createdAt": { "gte": "now-7d" } } } ],
  "must_not":[ { "term": { "archived": true } } ],
  "should": [ { "match": { "tags": "urgent" } } ]              // boosts score if matched
} } }
```

| Clause | Scores? | Cached? | Meaning |
|---|---|---|---|
| `must` | ✅ | ❌ | AND, contributes to relevance |
| `filter` | ❌ | ✅ | AND, fast yes/no — **prefer this** |
| `should` | ✅ | ❌ | OR-ish; boosts; `minimum_should_match` |
| `must_not` | ❌ | ✅ | NOT |

- **`match`** = analyzed (full-text). **`term`** = exact (use on `keyword`/numbers, **never on `text`**).
- `match_phrase` (order matters), `multi_match` (many fields), `wildcard`/`prefix` (slow — avoid leading `*`).
- **Pagination**: `from/size` only for shallow pages (default cap 10k). Deep paging → **`search_after`** + sort; **`scroll`** for exports; PIT (point-in-time) for consistency.

---

## 📊 Relevance & aggregations (quick hits)

- Scoring algorithm = **BM25** (TF-IDF successor). Saturates term frequency, accounts for field length.
- Scores are **per-query, not comparable across queries**. Don't store "70% relevant" as absolute.
- `function_score` / boosting to bias by recency, popularity, etc.
- **Aggregations** run on **`doc_values`** (columnar, on-disk) — fast, off-heap; `text` fields need `fielddata` (heap-heavy, avoid).
  - **bucket** (`terms`, `date_histogram`, `range`) → group; **metric** (`avg`, `sum`, `cardinality`) → compute; **pipeline** → aggs of aggs.
  - `cardinality` = approximate distinct count (**HyperLogLog**), not exact.

---

## 🌐 Distributed read/write

- **Routing**: `shard = hash(_routing | _id) % num_primary_shards`. Same routing key → same shard (enables fast filtered reads, but risks hotspots).
- **Read = scatter–gather**: coordinator fans out to one copy of each shard → **query phase** (collect ids+scores) → **fetch phase** (get docs). Cost scales with shard count → don't over-shard.
- **Write**: goes to primary → replicated to replicas → ack when `wait_for_active_shards` met.
- **Consistency knobs**: `?refresh=wait_for` (search-visible before responding), `if_seq_no`/`if_primary_term` (optimistic concurrency).

---

## 🛡️ Cluster & HA

- **Node roles**: master (cluster state), data, ingest, coordinating. Dedicate masters in prod.
- **Quorum**: `(master_eligible / 2) + 1` must agree to elect a master → run **3 master-eligible** nodes (tolerates 1 loss). Avoid even numbers (split-brain).
- **Green / Yellow / Red**: all shards assigned / primaries OK but a replica missing / a **primary** missing (data unavailable).

---

## 🚀 Performance & scaling

- **Shard sizing**: ~10–50 GB each; too many small shards = overhead, too few = no parallelism.
- **ILM (Index Lifecycle Management)**: hot → warm → cold → delete; pair with **time-based indices** + aliases (`logs-2026.06.*`) for log/event data.
- **Force-merge** read-only old indices to 1 segment. **Disable `_source`/`norms`** only when you truly don't need them.
- Use **bulk API** for writes (1 request, many ops); size ~5–15 MB or a few thousand docs/batch.

---

## ⚡ Quick command reference

```bash
GET  /_cat/health?v                     # cluster health
GET  /_cat/indices?v&s=store.size:desc  # index sizes
GET  /_cat/shards?v                     # shard placement
GET  /my-index/_search { ...query... }  # search
GET  /my-index/_mapping                 # see field types
GET  /my-index/_settings                # shards/replicas/refresh
POST /my-index/_refresh                 # force searchable now
POST /_bulk  { ndjson actions }         # batched writes
GET  /my-index/_doc/<id>                # get by id (realtime, no refresh needed)
POST /my-index/_update_by_query         # bulk update matching docs
GET  /_tasks                            # watch long-running ops
```

---

## 🎯 Rapid-fire interview answers

- **"Why ES over a SQL `LIKE`?"** Inverted index makes full-text & ad-hoc filtering sub-linear; `LIKE '%x%'` can't use a B-tree and scans.
- **"Is a write immediately searchable?"** No — durable instantly (translog), searchable after refresh (~1s). Use `refresh=wait_for` if you must.
- **"`text` vs `keyword`?"** `text` = analyzed for search; `keyword` = exact for filter/sort/agg. Strings get both by default.
- **"Filter vs must?"** Filter = cached, no scoring; must = scored. Use filter unless you need relevance.
- **"How keep ES in sync with Postgres?"** ES is a derived view — CDC/outbox/Kafka pipeline writes to ES idempotently (fixed `_id`); on divergence, **rebuild from source**.
- **"Why not use ES as primary DB?"** No ACID transactions, near-real-time visibility, eventual consistency, no joins — it's a search/analytics index, not a system of record.
- **"Deep pagination?"** `search_after` (+ PIT), not `from/size` past 10k.
- **"Approximate vs exact distinct?"** `cardinality` = HyperLogLog (approx); exact needs `terms` agg (expensive).
