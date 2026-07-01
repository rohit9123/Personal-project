# 10 — JSONB: Storage, Operators & Indexing

> The interview trap isn't "do you know JSONB exists" — it's *when you should reach for it instead of columns*,
> and *how you make a query over it fast*. JSONB is a first-class binary type with its own operator set and a
> GIN index strategy. Reach for it for genuinely schemaless data; abuse it as a column-dodge and you lose the
> planner's statistics, constraints, and your sanity.

---

## 0. The mental model (read this first)

`json` is **text** — Postgres stores the exact bytes you sent and re-parses on every access. `jsonb` is a **decomposed binary tree** — parsed once on write, stored in a format that's fast to traverse and indexable. **Almost always use `jsonb`.** The only reason to keep `json` is if you need to preserve exact formatting, key order, and duplicate keys (e.g. you're a faithful passthrough/audit log of the original document).

Rule of thumb for the whole chapter: *columns for what you query and constrain; JSONB for the variable, sparse, or caller-defined remainder.*

---

## 1. WHAT

| | `json` | `jsonb` |
|---|--------|---------|
| Storage | Raw text, reparsed each access | Decomposed binary, parsed once |
| Whitespace / key order | **Preserved** | Normalized away |
| Duplicate keys | Kept | **Last wins**, deduped |
| Indexable (GIN) | No | **Yes** |
| Write cost | Cheaper (no parse) | Slightly higher (parse + encode) |
| Read/operator cost | Higher (reparse) | Lower |

Both share the same operators; the difference is the storage engine underneath. This chapter is about `jsonb`.

---

## 2. WHY (the problem it solves)

Relational schemas are rigid by design — and that rigidity is usually a *feature*. JSONB earns its place when the data is genuinely **not** uniform:

1. **Caller-defined / sparse attributes** — product attributes that differ per category, broker-specific KYC fields, feature flags per account. Modeling each as a column gives you hundreds of mostly-NULL columns.
2. **External payloads you store whole** — webhook bodies, third-party API responses, the full event in an **outbox** row ([09 §3.4](09-fintech-patterns.md)) where you want the exact document for replay/audit.
3. **Evolving shapes without migrations** — early-stage features where the shape changes weekly; JSONB lets you ship without an `ALTER TABLE` each time.

What you **give up** by putting data in JSONB (the trade-off you must state in an interview):
- **No column statistics** by default → the planner guesses selectivity (often the flat 0.1% default), leading to bad row estimates and bad plans.
- **No type safety / constraints** — `NOT NULL`, `CHECK`, `FOREIGN KEY` don't reach inside a document (you can bolt on `CHECK` expressions, but it's clumsy).
- **Bigger, TOASTed rows** — large documents go out-of-line to TOAST, adding I/O.
- **Verbose, easy-to-typo access** vs plain columns.

> The senior framing: **JSONB is not a substitute for schema design.** If you filter, join, or aggregate on a field, it should be a real column. JSONB is for the genuinely schemaless tail.

---

## 3. HOW (the internals & the operators)

### 3.1 Storage — binary tree + TOAST

On write, `jsonb` parses the document and stores it as a tree of typed values (objects keep keys sorted, which is what enables fast key lookup and the deduplication). If the encoded value exceeds the ~2 KB TOAST threshold, it's compressed and/or pushed to the TOAST table out-of-line — so a wide JSONB column doesn't bloat the main heap tuple, but reading it costs an extra fetch. This ties straight back to [01 — TOAST](01-mvcc-and-storage-internals.md).

A consequence for MVCC: updating *one field* of a JSONB document rewrites the **whole** value as a new tuple version (Postgres has no in-place sub-document update). Big documents updated often = bloat ([06](06-vacuum-bloat.md)).

### 3.2 The operators you must know cold

| Operator | Returns | Use |
|----------|---------|-----|
| `->` | `jsonb` | Get field/element **as JSON** (`data -> 'addr' -> 'city'`) |
| `->>` | `text` | Get field/element **as text** (`data ->> 'status'`) — use in `WHERE`/casts |
| `#>` / `#>>` | jsonb / text | Get by **path array** (`data #>> '{addr,city}'`) |
| `@>` | bool | **Containment** — "does left contain this sub-document?" (`data @> '{"status":"FILLED"}'`) |
| `<@` | bool | Contained-by (reverse of `@>`) |
| `?` / `?|` / `?&` | bool | Key exists / any-of / all-of (`data ? 'status'`) |
| `@?` / `@@` | bool | **JSONPath** match (`data @? '$.items[*] ? (@.qty > 100)'`) |

`@>` containment is the workhorse for filtering and is the operator GIN accelerates. Note `->>` for comparisons: `WHERE data ->> 'status' = 'FILLED'` (text), and cast for numbers/dates: `WHERE (data ->> 'qty')::int > 100`.

### 3.3 Indexing — the decision that makes or breaks the query

A plain B-tree on a whole `jsonb` column is almost useless (it can only do whole-value equality). Two real strategies:

**(a) GIN index — for containment / key-existence / JSONPath over many keys.**

```sql
-- Default GIN (jsonb_ops): indexes every key AND every value.
CREATE INDEX idx_orders_data ON orders USING gin (data);
-- Supports @>, ?, ?|, ?&, @?, @@

-- jsonb_path_ops: indexes only value paths — SMALLER & FASTER for @>,
-- but does NOT support the key-existence operators (?, ?|, ?&).
CREATE INDEX idx_orders_data ON orders USING gin (data jsonb_path_ops);
```

| | `jsonb_ops` (default) | `jsonb_path_ops` |
|---|----------------------|------------------|
| Index size | Larger | **Smaller** (hashes whole paths) |
| `@>` containment | ✅ | ✅ (faster) |
| `?` / `?|` / `?&` key existence | ✅ | ❌ |
| Pick when | You need key-existence queries | You only do `@>` containment |

**(b) Expression B-tree index — for one hot scalar field with range/sort/equality.**

```sql
-- If you constantly filter/sort by data->>'status', index just that as text:
CREATE INDEX idx_orders_status ON orders ((data ->> 'status'));

-- Numeric/date field needs a cast (and the query must match the cast):
CREATE INDEX idx_orders_qty ON orders (((data ->> 'qty')::int));
```

> The interview heuristic: **GIN for "which documents contain X" (containment/existence over many possible keys); expression B-tree for "this one field behaves like a column" (equality, ranges, ORDER BY).** GIN can't do range scans or sorting; an expression index can't do arbitrary containment.

### 3.4 Why estimates go wrong (and the fix)

Because there are no per-key statistics, the planner can badly misjudge how many rows a JSONB predicate matches and choose a bad plan. Mitigations: pull the hot field into a real **generated column** and index/analyze *that*, or accept GIN's bitmap scan. Postgres 12+ generated columns are the clean bridge:

```sql
ALTER TABLE orders
  ADD COLUMN status text GENERATED ALWAYS AS (data ->> 'status') STORED;
CREATE INDEX ON orders (status);   -- now a real column: stats, B-tree, planner-friendly
```

---

## 4. CODE / SQL — see it yourself

```sql
CREATE TABLE orders (
    id        bigserial PRIMARY KEY,
    account_id bigint NOT NULL,
    data      jsonb NOT NULL
);

INSERT INTO orders (account_id, data) VALUES
 (1, '{"symbol":"INFY","status":"FILLED","qty":50,"tags":["intraday"]}'),
 (1, '{"symbol":"TCS","status":"PENDING","qty":120,"tags":["delivery","margin"]}');

-- Containment (GIN-accelerated): all FILLED orders
SELECT id FROM orders WHERE data @> '{"status":"FILLED"}';

-- Extract a field as text / as int
SELECT data ->> 'symbol', (data ->> 'qty')::int FROM orders;

-- Key existence: orders that carry a 'tags' key
SELECT id FROM orders WHERE data ? 'tags';

-- JSONPath: any order with qty > 100
SELECT id FROM orders WHERE data @? '$ ? (@.qty > 100)';

-- Mutate: set a field (returns a new document; whole value is rewritten)
UPDATE orders SET data = jsonb_set(data, '{status}', '"CANCELLED"') WHERE id = 2;

-- Expand array elements into rows
SELECT id, tag FROM orders, jsonb_array_elements_text(data -> 'tags') AS tag;

-- Confirm the index is used
EXPLAIN ANALYZE SELECT id FROM orders WHERE data @> '{"status":"FILLED"}';
--  -> Bitmap Heap Scan ... -> Bitmap Index Scan on idx_orders_data
```

---

## 5. INTERVIEW ANGLES

**Q: `json` vs `jsonb` — which and why?**
A: Almost always `jsonb`. `json` stores the raw text and reparses it on every access, preserving exact whitespace, key order, and duplicate keys. `jsonb` parses once into a decomposed binary tree — slightly more expensive on write, but far cheaper to traverse, and crucially it's the only one that's **indexable** (GIN). I'd only keep `json` when I must reproduce the original document byte-for-byte, like an audit/passthrough log where formatting and duplicate keys are themselves the data.

**Q: When do you put data in JSONB vs columns?**
A: Columns for anything I filter, join, aggregate, sort, or constrain on — those need statistics, type safety, and B-tree indexes, all of which I lose inside a document. JSONB for the genuinely schemaless tail: caller-defined or sparse attributes, whole external payloads I store for replay (like an outbox event), or shapes that change too often to migrate each time. The failure mode I call out is using JSONB as a column-dodge — you end up with no planner statistics, no constraints, and bad row estimates. If a field becomes a query predicate, I promote it to a real column, often a `GENERATED ALWAYS AS (data->>'x') STORED` generated column so I get stats and a normal index.

**Q: How do you make a JSONB query fast?**
A: It depends on the access pattern. For "which documents contain X" — containment (`@>`) or key-existence (`?`) — I use a GIN index. Default `jsonb_ops` indexes every key and value and supports the existence operators; `jsonb_path_ops` only indexes value paths, so it's smaller and faster but only supports `@>`, not `?`. For a single hot scalar field that behaves like a column — equality, ranges, `ORDER BY` — GIN can't help (no range/sort), so I use an **expression B-tree index** on `((data->>'field'))`, casting for numbers. So the rule is GIN for containment across many keys, expression B-tree for one field used like a column.

**Q: What's the difference between `jsonb_ops` and `jsonb_path_ops`?**
A: Both are GIN opclasses. `jsonb_ops` (the default) creates index entries for every key and every value in the document, so it supports containment *and* key-existence operators (`?`, `?|`, `?&`) — but it's larger. `jsonb_path_ops` hashes whole *paths* to values, producing a smaller index with faster, more selective `@>` containment lookups, at the cost of not supporting the key-existence operators at all. If my workload is purely containment, `jsonb_path_ops` is the better pick; if I need `?`, I need the default.

**Q: What happens to MVCC and bloat when I update one field of a big JSONB document?**
A: Postgres has no in-place sub-document update — `jsonb_set` produces a brand-new value and the row is written as a whole new tuple version, just like any UPDATE. So a large document updated frequently generates a lot of dead tuples and bloat, and if it's TOASTed, each version rewrites the out-of-line data too. The mitigations are the usual MVCC ones — keep hot, frequently-mutated fields as separate narrow columns rather than inside a big document, and let autovacuum keep up. This is exactly why I don't store rapidly-changing state in a fat JSONB blob.

**Q: Why might a JSONB query pick a bad plan even with an index?**
A: Because by default there are no per-key statistics inside the document, so the planner falls back to crude default selectivity estimates for JSONB predicates and can wildly mis-estimate matching rows — which leads it to pick a seq scan or a nested loop when it shouldn't. The fix is to give the planner real statistics on the field that matters: promote it to a generated `STORED` column and index/analyze that, so the predicate becomes an ordinary, well-estimated column comparison.

---

## 6. ONE-LINE RECALL CARDS

- `jsonb` (binary, parsed once, **indexable**) over `json` (text, reparsed) — except for byte-exact audit passthrough.
- **Columns** for what you query/constrain; **JSONB** for the schemaless tail. JSONB ≠ excuse to skip schema design.
- Operators: `->` (json) vs `->>` (text), `#>>` (path), **`@>`** (containment — the workhorse), `?` (key exists), `@?`/`@@` (JSONPath).
- **GIN** index for containment/existence: `jsonb_ops` (default, supports `?`) vs `jsonb_path_ops` (smaller, faster `@>`, no `?`).
- **Expression B-tree** `((data->>'f'))` for a single field used like a column (equality/range/sort) — GIN can't range or sort.
- No per-key stats → planner misestimates → bad plans. Fix with a `GENERATED ... STORED` column + index.
- Updating one field rewrites the **whole** document tuple (MVCC) → bloat for big, hot docs; large values go to **TOAST**.

→ **Next:** back to the [Postgres index](index.md) — or tie it to [09 — Fintech Patterns](09-fintech-patterns.md) (the outbox row stores its event payload as JSONB).
