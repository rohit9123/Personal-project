# LeetCode Daily

A local Spring Boot web app that connects to your LeetCode account, filters out problems you've already solved, and picks 2 random unsolved problems for you to practice each day. Tracks your daily streak.

---

## Prerequisites

- Java 17+
- Maven 3.6+
- A LeetCode account (free tier works — paid-only problems are automatically skipped)

---

## One-Time Setup

### Step 1 — Get your session cookie

1. Log into [leetcode.com](https://leetcode.com) in your browser
2. Open DevTools → **Application** tab → **Cookies** → `https://leetcode.com`
3. Copy the value of `LEETCODE_SESSION`
4. *(Optional, for topic tags)* Also copy the value of `csrftoken`

### Step 2 — Paste into config

Open `src/main/resources/application.yml` and replace the placeholders:

```yaml
leetcode:
  session: eyJhbGciOiJIUzI1NiJ9...   # your LEETCODE_SESSION value
  csrf-token: abc123xyz               # your csrftoken value (optional)
```

> **Note:** `application.yml` is gitignored — your cookie will never be committed.  
> When your session expires (usually after 2–4 weeks), repeat this step.

---

## Running

```bash
cd leetcode-daily
mvn spring-boot:run
```

Open **http://localhost:8080** in your browser.

---

## Usage Walkthrough

### Day 1 — Starting fresh

When you open the app, it fetches your full problem list from LeetCode (~3400 problems), filters out everything you've already solved, and picks 2 random unsolved problems.

**What you see:**

```
⚡ LeetCode Daily                           🔥 0 days

[All]  [Easy]  [Medium]  [Hard]            ↻ New Picks

┌──────────────────────┐  ┌──────────────────────┐
│ # 763                │  │ # 1143               │
│ Partition Labels     │  │ Longest Common       │
│                      │  │ Subsequence          │
│ [Medium]             │  │ [Medium]             │
│                      │  │                      │
│ Greedy · Two Pointers│  │ Dynamic Programming  │
│                      │  │                      │
│  Open on LeetCode →  │  │  Open on LeetCode →  │
└──────────────────────┘  └──────────────────────┘

        [ ✓ Mark Today as Done (+1 streak) ]

                Total days practiced: 0
```

### Filtering by difficulty

Click **Easy**, **Medium**, or **Hard** to get problems in that range only.  
Click **All** to go back to the full unsolved pool.

```
[All]  [Easy ✓]  [Medium]  [Hard]          ↻ New Picks
```

The filter stays active — **New Picks** re-rolls within the same difficulty.

### Don't like the picks? Re-roll

Click **↻ New Picks** to get a different pair. Each click picks 2 new random problems from your unsolved pool.

### Solving the problems

Click **Open on LeetCode →** on either card. It opens the problem directly in a new tab — you solve it there as usual.

### Logging your session

Once you've solved (or attempted) your problems for the day, come back and click:

```
[ ✓ Mark Today as Done (+1 streak) ]
```

The button changes to:
```
✓ Today's session logged. Come back tomorrow!
```

And the streak counter in the header increments:
```
🔥 1 day
```

### Building a streak

| Day | Action | Streak |
|-----|--------|--------|
| Mon | Mark done | 🔥 1 day |
| Tue | Mark done | 🔥 2 days |
| Wed | Mark done | 🔥 3 days |
| Thu | *(skip)* | — |
| Fri | Mark done | 🔥 1 day *(reset)* |

The streak resets if you miss a day. Marking done more than once in the same day is a no-op.

---

## Topic Tags

Topic tags (Array, Dynamic Programming, Graph, etc.) appear on each card when `csrf-token` is configured. They're fetched live from LeetCode for the 2 selected problems.

**Without csrf-token:**
```
# 763  Partition Labels  [Medium]
```

**With csrf-token:**
```
# 763  Partition Labels  [Medium]
Hash Map · Two Pointers · String · Greedy
```

To enable: paste your `csrftoken` cookie value into `application.yml` under `csrf-token`.

---

## Refreshing Your Session

LeetCode sessions expire after a few weeks. When they do, the page shows:

```
⚠ Could not load problems: Empty response from LeetCode API. Session may be expired.
Update leetcode.session in application.yml and restart.
```

Fix:
1. Log into leetcode.com in your browser
2. Copy the new `LEETCODE_SESSION` cookie value
3. Paste it into `application.yml`
4. Restart: `Ctrl+C` then `mvn spring-boot:run`

---

## How It Works

```
Browser → Spring Boot (localhost:8080)
              │
              ├─ GET https://leetcode.com/api/problems/all/
              │   Cookie: LEETCODE_SESSION=...
              │   → returns all ~3400 problems with your solved status
              │
              ├─ Filters out: status="ac" (solved) + paid-only problems
              │
              ├─ Picks 2 random from remaining pool
              │
              └─ POST https://leetcode.com/graphql   ← only if csrf-token is set
                  → fetches topic tags for the 2 picked problems
```

The problem list is cached for the day — navigating and filtering does not re-fetch from LeetCode. Only restarting the app or opening it the next day triggers a fresh fetch.

Streak data is stored in `~/.leetcode-daily/streak.json`:
```json
{
  "lastSolvedDate" : "2026-04-16",
  "currentStreak" : 5,
  "totalDays" : 12
}
```

---

## Configuration Reference

```yaml
# src/main/resources/application.yml

leetcode:
  session: "..."       # Required. LEETCODE_SESSION cookie from leetcode.com
  csrf-token: "..."    # Optional. csrftoken cookie — enables topic tags on cards

server:
  port: 8080           # Change if 8080 is in use
```
