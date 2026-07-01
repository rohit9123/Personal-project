# Behavioral & Values Round — Zerodha SDE2 Prep

Most strong engineers under-prepare the behavioral round and over-prepare DSA. At SDE2, the behavioral
loop is a real filter — it decides level (SDE1 vs SDE2 vs senior) and tests whether you *owned* your work
or merely *touched* it. This track makes you answer the recurring questions cold, with concrete, quantified
stories drawn from your **actual projects**.

Each note follows the repo convention of being interview-first and example-driven:
- [01 — The STAR Method & Question Bank](01-star-method-and-question-bank.md) — how to structure an answer, the 6 question families, the full question list, and the anti-patterns that get you down-levelled.
- [02 — Story Bank](02-story-bank.md) — 7 ready-to-tell STAR stories built from your real projects, each tagged with the questions it answers. **Personalize the `[FILL]` markers** with your specifics.

## Why this matters at Zerodha specifically

Zerodha is not a typical VC startup. It's **bootstrapped, profitable, frugal, and engineering-led**
(CTO Kailash Nadh is famously vocal about simplicity, Go + Postgres, open source, and *not* over-engineering).
That culture shapes what the behavioral round rewards — and punishes:

| They reward | They are skeptical of |
|-------------|------------------------|
| **Simplicity & pragmatism** — solving the problem with the least machinery | Resume-driven development ("I introduced Kafka/k8s because…") |
| **Ownership** — you carried it from problem to production to on-call | "The team did X" with no clear personal contribution |
| **First-principles reasoning** — you understood *why*, not just *what* | Cargo-culting patterns/tools without justifying the trade-off |
| **Frugality & long-term thinking** — cost, maintainability, low churn | Chasing hype, premature scaling, throwing infra at problems |
| **Low ego & honesty** — you admit what failed and what you'd redo | Blaming others, inflating impact, never having been wrong |
| **Genuine interest in the domain** — markets, money correctness | Treating it as "just another backend job" |

> The meta-signal Zerodha looks for: *would this person add complexity we'll regret, or remove it?* Frame
> your stories so the answer is obvious. When you talk about your async pipeline or CQRS read models, lead
> with the **problem and the constraint**, not the technology — and mention the *simpler* option you
> considered and why you did or didn't take it.

## The 6 question families (everything maps to these)

1. **Hardest technical problem / debugging** — depth, rigor, systematic thinking.
2. **System you designed / a trade-off you made** — judgment, alternatives weighed.
3. **Ownership / going beyond your remit** — initiative, end-to-end responsibility.
4. **Conflict / disagreement** — collaboration, ego, how you handle being wrong.
5. **Failure / mistake** — honesty, learning, blast-radius management.
6. **Impact / scope & ambiguity** — turning a vague ask into shipped value.

## How to use this track

1. Read [01](01-star-method-and-question-bank.md) once to internalize the structure and anti-patterns.
2. From [02](02-story-bank.md), pick **4–5 stories** that together cover all 6 families (one strong story
   can answer 2–3 questions). Fill in the `[FILL]` personal specifics.
3. Practice each out loud in **≤ 2 minutes**, leading with Situation/Task in 2 sentences, spending most
   time on **Action (what *you* did)**, and ending with a **quantified Result + a Learning**.
4. Prepare your **questions for the interviewer** (last section of [01]) — at a place like Zerodha, asking
   about engineering culture and how they keep systems simple signals you'd fit.

→ **Start:** [01 — The STAR Method & Question Bank](01-star-method-and-question-bank.md)
