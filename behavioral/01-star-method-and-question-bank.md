# 01 — The STAR Method & Question Bank

> The behavioral round is not a vibe check — it's a structured evaluation. Interviewers are scoring you on
> specific signals (ownership, judgment, collaboration, impact). A rambling, chronological answer hides the
> signal. STAR surfaces it.

---

## 1. The STAR(L) structure

| Letter | What goes here | Time budget | The mistake to avoid |
|--------|----------------|-------------|----------------------|
| **S — Situation** | 1–2 sentences of context: the system, the stakes | ~15% | Don't spend 90 seconds on background |
| **T — Task** | *Your* specific responsibility / the problem you owned | ~10% | "The team had to…" — make it **I** |
| **A — Action** | The concrete steps **you** took, and *why* (the trade-offs) | **~55%** | Vague verbs ("worked on", "helped with") |
| **R — Result** | The outcome, **quantified** | ~15% | "It worked well" with no number |
| **L — Learning** | What you'd do differently / what it taught you | ~5% | Skipping it — this is the maturity signal |

The single most common failure: spending 70% of the answer on Situation and Action by the *team*, and 10%
on what *you* personally did. Flip it. The interviewer is evaluating **you**, so the word "I" should
dominate the Action section.

### The "I vs we" rule
Use "we" for context, "I" for contribution. *"We had a P95 latency alert firing across five endpoints.
**I** profiled the call graph, found an N+1 pattern, and replaced per-entity HTTP calls with a single batch
Elasticsearch query."* The interviewer must finish your story knowing exactly what **you** did.

---

## 2. What each question family is really testing

| Family | The hidden question | Lead your answer with |
|--------|---------------------|------------------------|
| Hardest technical problem | "How do you think when it's hard?" | The systematic process, not the lucky guess |
| System you designed / trade-off | "Is your judgment sound?" | The constraint + the alternative you rejected |
| Ownership / beyond remit | "Do you own outcomes or tasks?" | You noticed → you acted without being told |
| Conflict / disagreement | "Can you be wrong gracefully?" | Curiosity about their view, data over opinion |
| Failure / mistake | "Are you honest and safe?" | What broke, your role in it, blast-radius control |
| Impact / ambiguity | "Can you turn vague into shipped?" | How you scoped it down to something deliverable |

---

## 3. The question bank (practice these cold)

**Technical depth & debugging**
- Tell me about the hardest bug / production incident you debugged.
- Describe a performance problem you diagnosed and fixed. How did you measure it?
- Walk me through a system you designed end-to-end.
- Tell me about a time you had to learn a new technology quickly to ship something.

**Trade-offs & judgment**
- Describe a significant technical trade-off you made. What were the alternatives?
- Tell me about a time you chose the *simpler* solution over the more sophisticated one. (★ very Zerodha)
- When did you decide *not* to build / add something? Why?
- Tell me about a time you disagreed with a technical decision.

**Ownership & initiative**
- Tell me about a time you went beyond what was asked of you.
- Describe something you owned end-to-end, including running it in production.
- Tell me about a time you improved something nobody asked you to improve.

**Collaboration & conflict**
- Tell me about a disagreement with a teammate / your lead. How did it resolve?
- Describe a time you received critical feedback. What did you do with it?
- Tell me about a time you had to convince others to adopt your approach.

**Failure & growth**
- Tell me about a time you failed / shipped a bug / made a wrong call.
- What's a technical decision you regret? What would you do differently?
- Describe a project that didn't go as planned.

**Impact, scope & ambiguity**
- Tell me about your most impactful project and how you measured the impact.
- Describe a time you were given a vague problem. How did you approach it?
- Tell me about a time you had to balance speed vs quality / hitting a deadline.

---

## 4. Anti-patterns that get you down-levelled

- **The hero with no team** — you single-handedly saved everything; reads as ego / poor collaboration.
- **The passenger** — "we" for everything; no identifiable personal contribution → SDE1, not SDE2.
- **The tool-dropper** — listing technologies (Kafka, k8s, Redis) without the *why* or the trade-off.
- **No numbers** — impact with zero quantification. Always attach a metric (latency, throughput, rows, %, cost, time saved).
- **Never wrong** — no genuine failure story, or a fake one ("my weakness is I work too hard").
- **Over-engineering pride** — bragging about complexity you added. At Zerodha this is a *negative* signal; brag about complexity you *removed*.
- **Blaming** — the outage was "because of the other team / bad requirements." Own your slice.

---

## 5. Zerodha-specific framing tips

- For *any* design story, explicitly name the **simpler alternative** and why you did / didn't take it. This
  directly hits their "don't over-engineer" value.
- Mention **cost / maintainability / on-call burden**, not just throughput. Frugal, long-term thinking.
- When money is involved (it often is in your projects), surface **correctness / idempotency / consistency**
  reasoning — fintech cares about not losing or double-counting money.
- Show **genuine domain curiosity**. If asked "why Zerodha?", a real interest in markets / building reliable
  financial infrastructure beats "great brand / comp."

---

## 6. Questions to ask *them* (prepare 4–5)

Asking good questions is part of the evaluation. Zerodha-flavored ones:
- "How does the team decide when something is worth adding infrastructure for vs keeping it simple?"
- "What does on-call / production ownership look like for an SDE2 here?"
- "Where does the team still carry tech debt you've consciously chosen to live with, and why?"
- "How do you keep Postgres as the workhorse as load grows — what's the scaling philosophy?"
- "What separates an SDE2 from a senior engineer on this team?"

→ **Next:** [02 — Story Bank](02-story-bank.md) — turn your real projects into tellable STAR stories.
