# 14 — Specialized Data Types: HyperLogLog, Bitmaps, Bitfields & Geo

> The "core five" (string/hash/list/set/zset) cover 90% of Redis. This chapter is the other 10% interviewers
> love to probe: *"count uniques in 12 KB"*, *"track daily-active users for 50M accounts cheaply"*, and
> *"how do counters fit in a few bits."* These are all built on top of strings — they're encodings, not new
> objects — which is exactly the insight that gets you the offer.

---

## 1. WHAT

| Type | Command family | Backed by | What it gives you |
|------|----------------|-----------|-------------------|
| **HyperLogLog (HLL)** | `PFADD` / `PFCOUNT` / `PFMERGE` | A string (max **12 KB**) | *Approximate* cardinality (count of distinct elements) with ~0.81% error |
| **Bitmap** | `SETBIT` / `GETBIT` / `BITCOUNT` / `BITOP` / `BITPOS` | A string, addressed bit-by-bit | A dense array of booleans — presence/flags per integer offset |
| **Bitfield** | `BITFIELD` | A string, addressed as packed integers | Many small signed/unsigned counters packed into one string |
| **Geo** | `GEOADD` / `GEOSEARCH` / `GEODIST` | A **sorted set** (score = geohash) | Store points, query by radius/box, compute distances |

The unifying idea: **all four are clever interpretations of the Redis string** (Geo being the exception — it rides on a ZSET). No new internal object type is introduced; that's why they're cheap and why the encoding questions matter.

---

## 2. WHY (the trade-offs)

### 2.1 HyperLogLog — counting uniques when a Set is too expensive

To count distinct items *exactly* you'd store every item in a Set — memory grows linearly with cardinality. Counting 100M unique order IDs in a Set could cost gigabytes. HLL answers "how many distinct?" in a **fixed 12 KB regardless of cardinality**, trading exactness for ~0.81% standard error. You **cannot** ask "is X a member?" — HLL only counts, it doesn't store elements.

> Use HLL when: unique visitors, distinct symbols traded, distinct IPs hitting an API — and an ~1% error is acceptable. Use a Set when: you need exactness or membership tests.

### 2.2 Bitmaps — boolean-per-entity at 1 bit each

If you key users by a dense integer ID, "did user N do X today?" is one bit. 50M users = 50,000,000 bits ≈ **6 MB** for a full day's activity flag. A Set of 50M user IDs would be hundreds of MB. Bitmaps win massively **when IDs are dense integers and the value is boolean**. They lose (waste memory) when IDs are sparse — bit offset 1 billion allocates the whole 125 MB string even for one user.

### 2.3 Bitfields — packing many tiny counters

When you have millions of small counters (e.g. a per-user 8-bit "strikes" count), storing each as its own key wastes memory on key overhead and `redisObject` headers. `BITFIELD` packs them as fixed-width integers (`u8`, `i16`, …) inside one string, with atomic `INCRBY` and overflow control (`WRAP`/`SAT`/`FAIL`) in a single round trip.

### 2.4 Geo — radius queries without a spatial database

Geohashing maps 2D coordinates to a 1D 52-bit integer such that nearby points have nearby values. Redis stores that integer as a ZSET score, so a radius query becomes a **range scan over sorted scores** — `O(log N + M)`. Good enough for "branches/ATMs near me"; not a replacement for PostGIS on complex polygons.

---

## 3. HOW (the internals)

### 3.1 HyperLogLog internals — registers, sparse vs dense

HLL hashes each element to 64 bits. The first **14 bits** select one of **2¹⁴ = 16384 registers**; the remaining bits are scanned for the position of the first set bit (the "rank" — a run of leading zeros). Each register keeps the **maximum rank** ever seen for it. The intuition: a long run of leading zeros is rare, so observing one implies you've seen many distinct items. The cardinality estimate is a bias-corrected harmonic mean across all 16384 registers.

Two encodings:
- **Sparse** — for low cardinalities, stores only non-zero registers compactly (can be a few hundred bytes).
- **Dense** — once it grows, switches to a flat array of 16384 × 6-bit registers = **12 KB**.

`PFCOUNT` on a single key is `O(1)` — it reads a fixed 16384 registers regardless of cardinality. `PFMERGE` (and `PFCOUNT` over multiple keys) takes the per-register max across HLLs — this is why **HLLs are mergeable**: you can union daily HLLs into a monthly unique count with no double-counting.

> Gotcha: `PFCOUNT` can write — on a sparse HLL it may promote to dense and cache the estimate in the header, so it's not purely read-only on replicas in older versions. The **0.81%** figure is the *standard error* ($1.04/\sqrt{16384} \approx 0.81\%$) — i.e. the typical/expected relative error, **not a hard upper bound**. Any individual estimate can exceed it; roughly two-thirds of estimates fall within ±0.81% and almost all within a few times that.

### 3.2 Bitmap internals — addressing and memory

A bitmap is just a string; `SETBIT key offset 0|1` addresses bit `offset`. Setting a high offset **grows the string to cover it**, zero-filled — so memory is governed by the *highest* bit set, not the count. `BITCOUNT` (population count) and `BITOP AND/OR/XOR/NOT` operate across whole strings.

Key danger: `BITOP` and `BITCOUNT` on huge bitmaps are `O(N)` over the string length and run on the single thread — a `BITOP AND` across 30 daily 6 MB bitmaps touches ~180 MB and can stall every other client (ties back to [02 — the O(N) danger](02-single-thread-event-loop.md)). Use `BITCOUNT key start end` ranges or do heavy aggregation off the hot path.

### 3.3 Bitfield internals — typed sub-fields with overflow control

`BITFIELD key SET u8 #3 200 INCRBY u8 #3 100 OVERFLOW SAT INCRBY u8 #3 100`:
- `u8 #3` = the 4th unsigned-8-bit field (offset `3 × 8 = 24` bits). The `#` makes the offset field-relative.
- `OVERFLOW` controls behavior: `WRAP` (modulo, default), `SAT` (saturate at type max/min), `FAIL` (return nil on overflow). Overflow mode applies to the *following* operations in the same call.

All operations in one `BITFIELD` call execute **atomically** on the single thread — a packed counter array with no race window.

### 3.4 Geo internals — geohash on a ZSET

`GEOADD key lon lat member` encodes `(lon, lat)` into a 52-bit geohash and does a `ZADD` with that as the score. `GEOSEARCH ... BYRADIUS 5 km` computes the geohash ranges covering the search area and does `ZRANGEBYSCORE`-style scans, then filters by exact distance. Because it's a ZSET under the hood, `ZREM` removes a point and `ZCARD` counts points — the geo commands are sugar over sorted-set operations.

---

## 4. CODE / EXAMPLES

### 4.1 HyperLogLog — distinct symbols traded today, mergeable to a month

```bash
# Count distinct instruments traded per day — 12 KB per day regardless of volume
PFADD trades:2026-06-27 INFY RELIANCE TCS INFY      # INFY counted once
PFADD trades:2026-06-27 HDFCBANK
PFCOUNT trades:2026-06-27                            # ≈ 4 (±0.81%)

# Union a week into one distinct-symbols count — no double counting
PFMERGE trades:wk26 trades:2026-06-22 trades:2026-06-23 trades:2026-06-27
PFCOUNT trades:wk26
```

### 4.2 Bitmap — daily-active-users (DAU) and a 7-day "active any day"

```bash
# user_id is the bit offset; "1" = active today
SETBIT dau:2026-06-27 10042 1
SETBIT dau:2026-06-27 999   1
BITCOUNT dau:2026-06-27                 # today's active-user count

# Was user 10042 active today?  -> 1
GETBIT dau:2026-06-27 10042

# "Active on ANY of the last 7 days" — OR the 7 daily bitmaps into one
BITOP OR dau:last7 dau:2026-06-21 dau:2026-06-22 dau:2026-06-27
BITCOUNT dau:last7                      # weekly-active users
```

### 4.3 Bitfield — pack a per-user 8-bit "failed login" counter, saturating

```bash
# Field #500 = the 501st u8 counter; saturate at 255 instead of wrapping to 0
BITFIELD logins SET u8 #500 0
BITFIELD logins OVERFLOW SAT INCRBY u8 #500 1
BITFIELD logins GET u8 #500
```

### 4.4 Geo — nearest branches (Jedis, matching the track's Java examples)

```java
try (Jedis jedis = pool.getResource()) {
    jedis.geoadd("branches", 72.8777, 19.0760, "Mumbai-Fort");
    jedis.geoadd("branches", 77.5946, 12.9716, "Bengaluru-MG");

    // Branches within 50 km of a point, nearest first, with distance
    GeoRadiusParam p = GeoRadiusParam.geoRadiusParam().withDist().sortAscending();
    List<GeoRadiusResponse> near =
        jedis.geosearch("branches", new GeoCoordinate(72.83, 19.02),
                        50, GeoUnit.KM); // (use geosearch on Redis 6.2+)
}
```

---

## 5. INTERVIEW ANGLES

### Q: You need to count unique daily visitors to the trading app — 50M+ users. How, and why not a Set?

A: HyperLogLog. A Set storing every distinct user ID grows linearly — easily hundreds of MB to GB and unbounded. An HLL answers the cardinality question in a **fixed 12 KB** with ~0.81% error, which is fine for an analytics counter. I'd `PFADD` each user ID to a daily key and `PFCOUNT` it; for monthly uniques I `PFMERGE` the daily HLLs, which takes the per-register max so there's no double-counting. The trade-off I'd state explicitly: HLL only *counts* — it can't tell you whether a specific user visited (no membership), and it's approximate. If I needed membership or exactness, I'd use a Set (or a Bloom filter for probabilistic membership).

### Q: How does HyperLogLog actually estimate cardinality?

A: It hashes each element and uses the first 14 bits to pick one of 16384 registers; the rest of the hash is scanned for the position of the first set bit — the count of leading zeros. Each register stores the maximum run-length it has ever seen. The intuition is that long runs of leading zeros are statistically rare, so seeing a long run implies many distinct elements passed through. The final estimate is a bias-corrected harmonic mean across all registers, which damps outliers. Internally it starts in a compact *sparse* encoding for low counts and flips to a *dense* 16384 × 6-bit array (12 KB) as it grows. Because registers combine by max, two HLLs merge losslessly — that mergeability is the killer feature.

### Q: Track whether each of 50M users was active today, cheaply. What structure?

A: A Bitmap keyed by date, using the user's integer ID as the bit offset — one bit per user. 50M bits is about 6 MB for the whole day, versus hundreds of MB for a Set of IDs. `SETBIT dau:date userId 1` to mark active, `BITCOUNT` for the active count, `GETBIT` to check one user. For "active any day this week" I `BITOP OR` the seven daily bitmaps. The caveat I'd raise: this is only cheap when IDs are **dense integers** — a sparse ID like 2 billion would balloon the string to 250 MB because the bitmap allocates up to the highest offset. And `BITOP`/`BITCOUNT` over large bitmaps are O(N) on the single thread, so I'd range-bound them or aggregate off the hot path.

### Q: When would you use BITFIELD instead of many small keys?

A: When I have a large array of small fixed-width counters — say a per-user 8-bit retry count. Storing each as its own key wastes memory on key strings and per-object headers, and costs a round trip each. `BITFIELD` packs them as `u8`/`i16`/etc. inside one string, supports atomic `INCRBY` across several fields in a single call, and gives overflow control — `SAT` to clamp at the type max, `FAIL` to reject, `WRAP` to roll over. So it's both denser and atomic. The downside is it's awkward to scan/expire individual fields and you must manage offsets yourself.

### Q: How does Redis do geospatial radius queries without a spatial index?

A: Geo is sugar over a sorted set. `GEOADD` encodes longitude/latitude into a 52-bit geohash — an interleaving that maps 2D space to 1D so nearby points get nearby integers — and stores it as the ZSET score. A radius query computes the geohash ranges that cover the search area and does sorted-set range scans, then filters survivors by exact great-circle distance. So `GEOSEARCH` is `O(log N + M)`, and because it's really a ZSET, `ZREM`/`ZCARD` work on the points. It's great for "things near me" at city scale; for complex polygons or huge datasets I'd reach for PostGIS instead.

### Q: Are these structures safe on the single thread?

A: Mostly, but with the same O(N) caveat as everything else. `PFADD`/`SETBIT`/`GETBIT`/single `BITFIELD` ops are cheap. The traps are `BITOP`/`BITCOUNT` over multi-MB bitmaps and `PFMERGE` over many HLLs — these are O(N) in string length and block every other client while they run. In production I keep those aggregations off the request path (background job, or `BITCOUNT` with start/end ranges) — exactly the discipline from the single-threaded chapter.

---

## 6. ONE-LINE RECALL CARDS

* **HyperLogLog** counts distinct elements in a fixed **12 KB** with a **0.81%** *standard* (typical) error, not a hard cap — counts only, no membership.
* HLL = 16384 registers; first 14 hash bits pick a register, the rest give a leading-zero rank; registers combine by **max**, so HLLs **merge** losslessly (`PFMERGE`).
* **Bitmap** = 1 bit per dense integer ID; 50M users ≈ 6 MB. Cheap only for **dense** IDs — a sparse high offset allocates the whole string.
* `BITCOUNT` = popcount; `BITOP AND/OR/XOR` combine bitmaps — but both are **O(N)** on the single thread.
* **Bitfield** packs many fixed-width counters (`u8`/`i16`…) in one string, atomic `INCRBY` with `OVERFLOW WRAP|SAT|FAIL`.
* **Geo** rides on a **ZSET** (score = 52-bit geohash); `GEOSEARCH` is a sorted-set range scan, `O(log N + M)`.
* All four are **string interpretations** (Geo on ZSET) — no new object type, which is why they're memory-cheap.
* Use HLL/Bitmap for **analytics-scale approximate/boolean** answers; use Set for **exactness/membership**.

---

**Next:** back to the [Redis index](index.md) — or tie it together with [13 — Postgres + Redis System Design](13-postgres-redis-system-design.md).
