# 02 — Story Bank

> Seven STAR stories built from your real projects (per `resume-question-explanation/`). Each is tagged with
> the question families it answers and trimmed to a ~2-minute spoken length. **`[FILL]` markers are details
> only you know** — replace them before practicing. Don't invent numbers; use the real ones or say "roughly."

**Coverage map — pick 4–5 that span all 6 families:**

| Story | Hard tech | Design/trade-off | Ownership | Conflict | Failure | Impact/ambiguity |
|-------|:--:|:--:|:--:|:--:|:--:|:--:|
| 1. N+1 latency incident | ★ | | ★ | | | ★ |
| 2. Async report pipeline | | ★ | ★ | | | ★ |
| 3. LMS CQRS read models | | ★ | | | | ★ |
| 4. ES risk aggregations | ★ | ★ | | | | ★ |
| 5. SSE notification platform | | ★ | ★ | | | ★ |
| 6. Learning Kafka deeply | ★ | | ★ | | | |
| 7. Failure / disagreement | | | | ★ | ★ | |

---

## Story 1 — The P95 latency incident (N+1 + connection-pool starvation)
**Answers:** hardest bug · performance · ownership · impact
**Project:** Emission Studio Router (`emission-n1-latency-fix.md`)

- **S:** Our Emission Studio router started firing P95 latency alerts — responses over 3 seconds across five
  endpoints — and it was intermittent, which made it hard to catch.
- **T:** I owned the diagnosis and fix. The pressure was that it was user-facing and the cause wasn't obvious
  from the dashboards.
- **A:** Rather than guess, I profiled the request call graph and found the endpoints were making a
  **per-entity downstream HTTP call** to enrich each record — a classic **N+1 pattern**. Under load, those
  fan-out calls **starved the WebFlux connection pool**, so requests queued waiting for a connection, which
  is what produced the spiky P95. I replaced the per-entity calls with a **single batched Elasticsearch
  query**, collapsing the enrichment from **O(N) calls to O(1)**. I also [FILL: tuned pool size / added a
  bounded concurrency / added a metric] so we'd catch regressions earlier.
- **R:** P95 dropped from **>3s back under [FILL: target, e.g. ~300ms]** across all five endpoints, and the
  connection-pool starvation disappeared.
- **L:** The lesson was that the symptom (latency) was two layers away from the cause (fan-out exhausting a
  shared resource). I now treat "intermittent latency under load" as a resource-contention question first,
  and I add per-dependency call-count metrics so an N+1 shows up immediately.

> **Zerodha framing:** lead with the *measurement* — you didn't tune randomly, you profiled, found the
> structural cause, and fixed it with a *simpler* call pattern (fewer calls), not more infrastructure.

---

## Story 2 — Async report download pipeline (Kafka, 300K+ rows)
**Answers:** system design · trade-off · ownership · impact
**Project:** `async-report-download-pipeline.md`

- **S:** Users needed to export large reports — **300K+ rows** — but doing it synchronously in the request
  would time out and tie up request threads.
- **T:** I designed and built an **asynchronous, Kafka-based pipeline** to generate and deliver these reports
  reliably, with progress tracking.
- **A:** I moved report generation off the request path: the API enqueues a job and returns immediately with
  a job ID; a **Kafka** consumer generates the report in the background, with **retry handling** for transient
  failures and **background job-status tracking** so the user can poll/get notified on completion. I chose
  this over [FILL: the simpler option you considered, e.g. a synchronous stream / a DB-backed job table] because
  [FILL: throughput / decoupling / the volume justified it]. I made the consumer **idempotent** so a retry or
  redelivery wouldn't produce a duplicate report.
- **R:** Reports of 300K+ rows generate and deliver reliably without blocking request threads, and failures
  retry instead of silently dropping. [FILL: any latency/throughput/adoption number].
- **L:** [FILL — e.g. "I'd add backpressure / dead-letter handling earlier" or "monitoring job age was the
  thing I added after the first incident"].

> **Zerodha framing:** be ready for the push-back *"did you really need Kafka for this?"* — have an honest
> answer about the volume and the decoupling, and name the simpler design you weighed.

---

## Story 3 — LMS dashboard read models (event-driven CQRS)
**Answers:** trade-off (simpler-vs-complex) · design · impact
**Project:** `lms-dashboard-kafka-projectors.md`

- **S:** Dashboard query APIs were slow because they did **costly runtime joins** across data owned by
  multiple distributed services.
- **T:** I designed event-driven **read models** so queries hit pre-joined data instead of joining at request
  time.
- **A:** Using **Kafka projector flows**, I consumed domain events and **denormalized** the data into
  purpose-built **PostgreSQL** read tables shaped exactly for the dashboard's queries — a **CQRS** split
  between the write side (normalized, service-owned) and the read side (denormalized, query-optimized). The
  trade-off I accepted was **eventual consistency** on the read side in exchange for eliminating the runtime
  joins. I made the projectors idempotent and ordered per aggregate so replays/duplicates were safe.
- **R:** Dashboard queries went from join-heavy to single-table reads, [FILL: latency improvement], and the
  read side scales independently of the write services.
- **L:** CQRS earns its complexity only when read and write patterns genuinely diverge; I'm now careful to
  apply it where the join cost is real, not by default.

> **Zerodha framing:** this is a strong "justified complexity" story — you added a read model *because* the
> joins were measurably expensive, and you can articulate the consistency trade-off you took on.

---

## Story 4 — Elasticsearch aggregations over 5M+ records
**Answers:** hard tech · design · impact at scale
**Project:** `risk-management-es-aggregations.md`

- **S:** We needed **disruption analytics** — multi-dimensional filtering and aggregation — over **5M+
  shipment and disruption records**, fast enough for an interactive UI.
- **T:** I designed the **Elasticsearch aggregation and multi-dimensional filtering** layer that powers it.
- **A:** I modeled the data and queries so the heavy lifting happened as **ES aggregations** (running on
  doc_values, not row scans) with filter-context predicates for the multi-dimensional filters, [FILL:
  mapping/keyword-vs-text decisions, any nested handling]. This kept analytics queries off the OLTP database,
  which couldn't have served this interactively.
- **R:** High-performance disruption analytics over 5M+ records with scalable query execution, [FILL: query
  latency / dimensions supported].
- **L:** [FILL — e.g. "getting the keyword/text mapping and aggregation sizing right up front mattered more
  than query tuning later"].

> **Zerodha framing:** you can tie this straight to your `elasticsearch/` notes — be ready to explain *why ES
> and not Postgres* here (inverted index + aggregations over high-cardinality, ad-hoc filters), and that ES
> is a secondary index, not the source of truth.

---

## Story 5 — Real-time SSE notification platform (Redis Streams)
**Answers:** system design · ownership · ambiguity
**Project:** `sse-notification-platform.md`

- **S:** We needed real-time, bell-icon notifications across microservices, surviving client disconnects and
  working across multiple service instances.
- **T:** I delivered the **SSE notification platform** end-to-end.
- **A:** I used **Server-Sent Events** for the push channel and **Redis Streams** with consumer groups for
  **load-balanced event processing** across instances, plus **persistent notification state** so a user's
  unread notifications survive, and **graceful reconnection** so a dropped SSE connection resumes without
  losing events. I chose SSE over WebSockets because [FILL: one-way push, simpler, HTTP-friendly] and Redis
  Streams over plain pub/sub because pub/sub is fire-and-forget — a disconnect would lose events, whereas
  Streams persist and support consumer-group acks.
- **R:** Reliable real-time notifications across services with reconnection and persisted state, supporting
  both async upload and download workflows. [FILL: scale / users].
- **L:** [FILL — e.g. handling the reconnection/replay window correctly was the subtle part].

> **Zerodha framing:** your Redis track (pub/sub vs streams) backs this directly — the "why Streams not
> pub/sub" reasoning is exactly the kind of trade-off they probe.

---

## Story 6 — Going deep on Kafka for risk-management
**Answers:** learning new tech · hard tech · ownership
**Project:** `kafka_deep_dive.md` (PR #628, risk-management services)

- **S:** I was working on the Kafka architecture across the risk-management microservices [FILL: and was
  relatively new to Kafka internals at the time].
- **T:** To make the right design and config calls, I needed to understand Kafka well beyond the API —
  delivery semantics, ordering, consumer groups, exactly-once.
- **A:** Instead of treating it as a black box, I went deep: [FILL: specific things — e.g. partitioning &
  ordering choices, idempotent producer / acks, consumer-group rebalance behavior, EOS / outbox]. I [FILL:
  drove PR #628 / set the conventions for X]. I documented it so the team shared the same mental model.
- **R:** [FILL — e.g. correct ordering/idempotency guarantees in the risk flows, fewer rebalance/duplicate
  incidents].
- **L:** Understanding the internals (not just the client API) is what let me make config decisions
  confidently rather than copy-pasting — which is why I keep deep notes on the systems I use.

> **Zerodha framing:** this signals first-principles learning and ownership — exactly their value of
> understanding *why*, not cargo-culting.

---

## Story 7 — Failure / disagreement (TEMPLATE — personalize fully)
**Answers:** conflict · failure
**This one you must supply from real experience** — interviewers detect fabricated conflict/failure stories.
Use this scaffold:

- **S:** [FILL: the situation — a decision you disagreed with, or a mistake you made, e.g. a config/deploy
  that caused an incident, or a design you pushed that a teammate/lead questioned].
- **T:** [FILL: your responsibility in it].
- **A (conflict version):** [FILL: how you raised it — you asked questions to understand their reasoning
  first, brought **data** rather than opinion, proposed a small experiment/spike, and were willing to be
  wrong]. **Key signal: show you'd commit to the team's decision even if it went against you.**
- **A (failure version):** [FILL: what broke, that you **owned it immediately**, how you contained the
  blast radius (rollback, feature flag, comms), and the durable fix — e.g. a test, a guardrail, an alert].
- **R:** [FILL: the outcome — resolved how, what shipped].
- **L:** [FILL: the concrete change you made to how you work afterwards — this is the whole point of the story].

> **Two anti-patterns to avoid here:** (1) a "fake failure" like "I cared too much"; (2) blaming someone
> else. Pick a real, bounded mistake where the *learning* is the hero.

---

## Final prep checklist

- [ ] Picked 4–5 stories covering all 6 families (use the map at the top).
- [ ] Replaced every `[FILL]` — especially numbers — with real specifics.
- [ ] Each story tells in **≤ 2 minutes**, with "I" dominating the Action.
- [ ] Story 7 (conflict/failure) is a **real** experience, learning-focused.
- [ ] For each design story, you can name the **simpler alternative** you considered.
- [ ] Prepared 4–5 questions to ask them ([01 §6](01-star-method-and-question-bank.md)).
