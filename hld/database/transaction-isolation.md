# Transaction Isolation (DB Internals)

---

## Quick Summary (TL;DR)

- When multiple users/transactions touch the database at the same time, weird things can happen — one user sees half-finished work of another, data goes missing, or invariants break silently.
- **Isolation levels** are the database's way of saying "here's how much protection you get against these weird things."
- **Read Committed** (PostgreSQL default): You'll never see uncommitted ("dirty") data, but you might see different results if you read the same thing twice.
- **Snapshot Isolation / MVCC**: You get a frozen snapshot of the database at the moment your transaction started. No matter what others do, your view doesn't change.
- **Write Skew**: The sneakiest bug — two transactions each make a valid decision, but the *combined* result violates a rule. Neither snapshot isolation nor row locks catch this.
- **Phantom Writes**: A query returns different rows the second time you run it, because another transaction inserted new data that matches your WHERE clause.

---

## 🤓 Noob Jargon Buster

* **Transaction**: A group of database operations (reads + writes) that should happen as one atomic unit. Either ALL of them succeed, or NONE of them do.
* **Dirty Read**: You read data that another transaction wrote but **hasn't committed yet**. If that transaction rolls back, you just read data that "never existed."
* **Dirty Write**: You overwrite data that another transaction wrote but hasn't committed. Now the data is a mess of two half-finished transactions.
* **Commit**: Making a transaction's changes permanent. Before a commit, changes are "tentative" and can be rolled back.
* **MVCC (Multi-Version Concurrency Control)**: Instead of locking data, the database keeps **multiple versions** of each row. Each transaction sees the version that was current when it started. It's like each transaction gets its own private copy of the database.

---

## Real-World Analogy

Think of a shared bank account viewed by two people on their phones:

💳 **Read Committed** = Every time you refresh the app, you see the latest *confirmed* balance. But if you check the balance, go make coffee, and check again — it might have changed because your partner spent money in between.

📸 **Snapshot Isolation** = When you open the app, it takes a **photo** of your account at that moment. For the entire time you're browsing, you see that photo — even if your partner is spending money right now. You won't see their changes until you close and reopen the app.

🏥 **Write Skew** = Two doctors are on call tonight. Both check the schedule and see "2 doctors on call." Both independently think "the other person can cover" and remove themselves. Result: **0 doctors on call.** Each decision was valid individually, but together they broke the rule "at least 1 doctor must be on call."

👻 **Phantom** = You count the number of items in your cart: 3 items. While you're checking out, your partner adds an item via a shared account. You count again: 4 items. The 4th item is a "phantom" — it appeared out of nowhere between your two counts.

---

## 1. Read Committed Isolation

### What Problem Does It Solve?

Without any isolation, you could see **uncommitted data** from other transactions. This is dangerous:

```
WHAT COULD GO WRONG WITHOUT READ COMMITTED:

Transaction A (transferring $100):     Transaction B (checking balance):
BEGIN;
UPDATE accounts SET balance = 400      
  WHERE id = 1; -- was 500             

                                       BEGIN;
                                       SELECT balance FROM accounts
                                         WHERE id = 1;
                                       → sees 400 (UNCOMMITTED!)

ROLLBACK; -- Oops, undo the transfer!
-- balance is back to 500

                                       -- But Transaction B already saw 400
                                       -- It read data that NEVER EXISTED
                                       -- This is a "dirty read" ☠️
```

### What Read Committed Guarantees

Two simple promises:

| Promise | What It Means |
|---------|---------------|
| **No dirty reads** | You will only ever see data that has been **committed** (finalized). |
| **No dirty writes** | You can only overwrite data that has been **committed**. Two uncommitted transactions won't step on each other's writes. |

### How It Works (Under the Hood)

**Preventing dirty writes — row-level locks:**

```
Transaction A:                      Transaction B:
BEGIN;
UPDATE accounts SET balance = 400    
  WHERE id = 1;                     
  🔒 Acquires lock on row id=1     

                                    BEGIN;
                                    UPDATE accounts SET balance = 600
                                      WHERE id = 1;
                                    🚫 BLOCKED! Waiting for A's lock...

COMMIT;
  🔓 Releases lock                 
                                    ✅ Lock acquired! Proceeds with update.
                                    COMMIT;
```

Simple: if someone's writing to a row, wait until they're done.

**Preventing dirty reads — remembering the old value:**

The database does NOT use read locks (that would be too slow). Instead, it keeps the old committed value around:

```
Row: accounts (id=1)

Currently committed value: balance = 500
Uncommitted value (Txn A is writing): balance = 400

When Txn B reads this row:
  → DB sees: "Txn A hasn't committed yet"
  → DB returns the OLD committed value: 500 ✅

When Txn A commits:
  → Committed value becomes 400
  → Now Txn B would read 400
```

### What Read Committed Does NOT Protect Against

Here's the catch — you can still see **inconsistent data** across multiple reads:

```
SCENARIO: Alice has $500 in Account 1, $0 in Account 2. Total = $500.
A transfer of $100 is happening: Account 1 → Account 2.

Transaction A (the transfer):          Transaction B (checking total):
BEGIN;                                 BEGIN;
                                       SELECT balance FROM accounts
                                         WHERE id = 1;  → $500 ✅

UPDATE accounts SET balance = 400       
  WHERE id = 1;  -- deduct $100       
UPDATE accounts SET balance = 100
  WHERE id = 2;  -- add $100
COMMIT;

                                       SELECT balance FROM accounts
                                         WHERE id = 2;  → $100
                                       
                                       -- Txn B thinks: total = $500 + $100 = $600
                                       -- But real total is still $500!
                                       -- Txn B saw Account 1 BEFORE the transfer
                                       -- and Account 2 AFTER the transfer 😱
```

This is called a **non-repeatable read** (or **read skew**). It's a problem for:
- ❌ **Database backups** (you'd copy an inconsistent snapshot)
- ❌ **Analytics queries** (aggregations over changing data)
- ❌ **Integrity checks** (verifying sums, counts, etc.)

For these, you need **Snapshot Isolation**.

---

## 2. Snapshot Isolation (MVCC)

### What Problem Does It Solve?

Read Committed lets you see different data each time you read (non-repeatable reads). Snapshot Isolation says: **"When your transaction starts, I take a photo of the entire database. You see ONLY that photo for your entire transaction."**

### The Big Idea: Multi-Version Concurrency Control (MVCC)

Instead of keeping just ONE version of each row, the database keeps **multiple versions**:

```
Row: accounts (id=1)

  Version 1: balance = 1000  |  created by Transaction #100  |  committed ✅
  Version 2: balance = 500   |  created by Transaction #105  |  committed ✅
  Version 3: balance = 200   |  created by Transaction #110  |  still running ⏳
```

Each version is tagged with:
- **Who created it** (which transaction ID)
- **Whether that transaction committed** (is this version "official"?)

### Which Version Do You See? (The Visibility Rule)

When your transaction reads a row, it follows this simple rule:

> **"Show me the latest version that was committed BEFORE my transaction started."**

Let's walk through an example:

```
Timeline:
  Transaction #100: INSERT accounts(id=1, balance=1000) → COMMITTED
  Transaction #105 starts (takes snapshot here 📸)
  Transaction #103: UPDATE balance = 500 WHERE id=1 → COMMITTED  
  Transaction #108: UPDATE balance = 200 WHERE id=1 → COMMITTED

What does Transaction #105 see?

  Version balance=1000 (by Txn #100) — committed, before my snapshot ✅
  Version balance=500  (by Txn #103) — committed, before my snapshot ✅ ← latest!
  Version balance=200  (by Txn #108) — committed, but AFTER my snapshot ❌

  → Transaction #105 sees balance = 500
```

Even though balance has been updated to 200 by a committed transaction, Transaction #105 doesn't see it because that update happened after its snapshot.

### MVCC in Plain English

```
Think of it like this:

📸 When your transaction starts, the database takes a PHOTO of everything.

For the rest of your transaction:
  - You see ONLY what was in that photo
  - Other people can change things (and you won't see it)
  - You can make your own changes (others won't see them until you commit)

It's like everyone is working on their own COPY of the database.
```

### How Does the Database Clean Up Old Versions?

Old versions pile up — nobody needs the `balance = 1000` version if all active transactions can see the newer one.

**Garbage collection (VACUUM in PostgreSQL):**
The database periodically checks: "Is any active transaction still using this old version?" If not, delete it.

```
Active transactions: #200, #205, #210 (all started after #110)

Version balance=1000 (by Txn #100) → No active txn needs this → DELETE ♻️
Version balance=500  (by Txn #105) → No active txn needs this → DELETE ♻️
Version balance=200  (by Txn #110) → This is the latest → KEEP ✅
```

### What Snapshot Isolation Prevents (vs Read Committed)

| Anomaly | Read Committed | Snapshot Isolation |
|---------|:-:|:-:|
| Dirty reads (seeing uncommitted data) | ✅ Prevented | ✅ Prevented |
| Dirty writes (overwriting uncommitted data) | ✅ Prevented | ✅ Prevented |
| Non-repeatable reads (same row, different value) | ❌ Can happen | ✅ Prevented |
| Lost updates (two read-modify-writes) | ❌ Can happen | ✅ Prevented* |
| **Write skew** | ❌ Can happen | ❌ **Still happens!** |
| **Phantom reads** | ❌ Can happen | ❌ **Still happens!** |

*Most databases detect lost updates under snapshot isolation using "first-committer-wins."

### Lost Update Prevention (First-Committer-Wins)

```
SCENARIO: Two users both add $100 to an account with $500.

Transaction A:                        Transaction B:
BEGIN; (snapshot: balance=500)         BEGIN; (snapshot: balance=500)
Read balance → 500                    Read balance → 500
New balance = 500 + 100 = 600         New balance = 500 + 100 = 600
UPDATE balance = 600                  UPDATE balance = 600
COMMIT; → ✅ Success                  COMMIT; → ❌ ABORT!

Why aborted? Both wrote to the SAME row based on the SAME snapshot.
Database detects: "Txn A already committed a change to this row.
Txn B's snapshot is stale." → Txn B retries with fresh data.
```

---

## 3. Write Skew & Phantom Writes

### Write Skew — The Sneakiest Bug

Write skew is the hardest concurrency bug to understand. It happens when:
1. Two transactions **read the same data**
2. They each make a decision based on what they read
3. They each **write to DIFFERENT rows**
4. The combined result breaks an invariant

**Why is this sneaky?** Because each transaction individually did nothing wrong! And because they write to different rows, there's no write-write conflict for the database to detect.

### The Classic Example: On-Call Doctors

```
RULE: At least 1 doctor must always be on call.
CURRENT STATE: Alice = on_call ✅, Bob = on_call ✅ (2 doctors on call)

Alice's Transaction:                    Bob's Transaction:
BEGIN;                                  BEGIN;
SELECT COUNT(*) FROM doctors            SELECT COUNT(*) FROM doctors
  WHERE on_call = true;                   WHERE on_call = true;
  → Result: 2                            → Result: 2

"2 doctors on call.                     "2 doctors on call.
 If I leave, there's still 1.           If I leave, there's still 1.
 Safe to go home!"                       Safe to go home!"

UPDATE doctors                          UPDATE doctors
  SET on_call = false                     SET on_call = false
  WHERE name = 'Alice';                   WHERE name = 'Bob';
COMMIT; ✅                              COMMIT; ✅

RESULT: 0 doctors on call! 🚨 RULE VIOLATED!
```

**Why didn't the database catch this?**
- Alice wrote to Alice's row. Bob wrote to Bob's row. **Different rows → no conflict detected.**
- Under snapshot isolation, both transactions saw count = 2 at the start. Their snapshots were valid.
- The database doesn't know about the application rule "at least 1 doctor on call."

### More Write Skew Examples

| Scenario | The Rule | What Goes Wrong |
|----------|----------|-----------------|
| **Meeting room booking** | No double-bookings for same room + time | Two people check "is room free?" → both see yes → both book it |
| **Username registration** | Usernames must be unique | Two people check "is 'coolname' available?" → both see yes → both register it |
| **Multiplayer game** | Two figures can't be on the same square | Two players check the square is empty → both move there |
| **Bank overdraft** | Total across all accounts ≥ 0 | Two withdrawals from different accounts, total goes negative |

### The Pattern Behind Write Skew

Every write skew follows the same pattern:

```
Step 1: SELECT (read some condition)    ← "Are there enough doctors?"
Step 2: DECIDE (application logic)      ← "Yes, I can leave"
Step 3: WRITE (update something)        ← "Set myself to off-call"

The problem: Step 3 invalidates the condition that Step 1 checked,
but the transaction doesn't re-check!
```

### Phantom Writes — The Ghost Rows

A **phantom** is when new rows appear (or disappear) between two reads of the same query.

```
SCENARIO: Booking a meeting room.

Transaction A:                           Transaction B:
BEGIN;
SELECT * FROM bookings
  WHERE room = 'A' AND time = '10am';
  → Result: EMPTY (room is free!)

                                         BEGIN;
                                         INSERT INTO bookings
                                           (room, time, user)
                                           VALUES ('A', '10am', 'Bob');
                                         COMMIT; ✅

-- A didn't see Bob's booking (snapshot!)
INSERT INTO bookings
  (room, time, user)
  VALUES ('A', '10am', 'Alice');
COMMIT; ✅

RESULT: Room A at 10am is double-booked! 💥
```

**Why this is different from write skew:**
- Write skew: both transactions read existing rows and write to different existing rows.
- Phantoms: the problem is that a **new row was inserted** that would have changed the result of the original query.

**Why locking doesn't help with phantoms:**
You can't lock a row **that doesn't exist yet**. Transaction A checked for bookings and found none — there were no rows to lock!

### How to Prevent Write Skew & Phantoms

From simplest to most robust:

#### Option 1: `SELECT ... FOR UPDATE` (Locks Existing Rows)

```sql
BEGIN;
-- Lock ALL rows matching this condition
SELECT * FROM doctors 
  WHERE on_call = true 
  FOR UPDATE;  -- 🔒 Nobody can modify these rows until I commit

-- Safe to check and decide
-- If someone else tries to UPDATE these rows, they BLOCK
UPDATE doctors SET on_call = false WHERE name = 'Alice';
COMMIT;
```

⚠️ **Limitation:** Only works for **existing rows**. Can't prevent phantoms (newly inserted rows).

#### Option 2: Unique Constraints / CHECK Constraints

```sql
-- For the username example:
CREATE UNIQUE INDEX ON users(username);
-- Now two people can't register the same username — the database enforces it.

-- For the meeting room example:
CREATE UNIQUE INDEX ON bookings(room, time);
-- Database prevents duplicate room+time combinations.
```

✅ Works great when the invariant can be expressed as a database constraint.
❌ Not all rules can be expressed this way (e.g., "at least 1 doctor on call").

#### Option 3: Materializing Conflicts (Creating Lock Targets)

For the meeting room problem, pre-create all possible room+timeslot combinations:

```sql
-- Create a row for every room+time combination
INSERT INTO room_slots (room, time, booked) VALUES ('A', '10am', false);
INSERT INTO room_slots (room, time, booked) VALUES ('A', '11am', false);
-- ... etc.

-- Now you CAN lock rows that "don't exist" (they do exist, just marked as unbooked)
BEGIN;
SELECT * FROM room_slots 
  WHERE room = 'A' AND time = '10am' 
  FOR UPDATE;  -- 🔒 Lock the slot
UPDATE room_slots SET booked = true WHERE room = 'A' AND time = '10am';
COMMIT;
```

⚠️ This is ugly and error-prone — you're polluting your schema for concurrency control.

#### Option 4: Serializable Isolation (Nuclear Option)

Use the database's **Serializable** isolation level (see the [concurrency-control.md](file:///Users/rohit.kumar.4/Documents/interview-prep/hld/database/concurrency-control.md) notes). This prevents ALL anomalies, including write skew and phantoms. Trade-off: lower performance.

---

## 4. The Complete Picture

```
      Most anomalies allowed                     Fewest anomalies
      (fastest)                                   (most correct)
      ◄─────────────────────────────────────────►

      Read              Snapshot            Serializable
      Committed         Isolation           
      ─────────         ─────────           ────────────
      ✅ No dirty       ✅ No dirty         ✅ No dirty
         reads/writes      reads/writes        reads/writes
      ❌ Non-repeat.    ✅ No non-repeat.   ✅ No non-repeat.
         reads             reads               reads
      ❌ Lost updates   ✅ No lost updates  ✅ No lost updates
      ❌ Write skew     ❌ Write skew       ✅ No write skew
      ❌ Phantoms       ❌ Phantoms         ✅ No phantoms

      PostgreSQL        MySQL InnoDB        Both (but slow)
      default           default
```

---

## Interview Angles

1. **"Explain Read Committed in simple terms."**
   → You only see data that's been committed. No half-finished transactions. But if you read the same row twice, the value might change if someone committed in between.

2. **"How does MVCC work?"**
   → The database keeps multiple versions of each row tagged with transaction IDs. Your transaction only sees versions created by transactions that committed before your transaction started. It's like each transaction gets a frozen snapshot.

3. **"What is write skew? Give me an example."**
   → Use the on-call doctor example. Two transactions read count=2, both decide to leave, both update *different* rows. Result: 0 doctors. Snapshot isolation can't catch this because there's no write-write conflict on the same row.

4. **"How is a phantom different from a non-repeatable read?"**
   → Non-repeatable read: same row, different *value*. Phantom: same query, different *set of rows* (new rows appeared or old rows disappeared).

5. **"How do you prevent write skew in practice?"**
   → Three options: (a) `SELECT ... FOR UPDATE` to lock the rows you depend on, (b) unique/check constraints if the rule is simple, (c) Serializable isolation level for full protection.

6. **"What's the default isolation level in PostgreSQL? MySQL?"**
   → PostgreSQL: Read Committed. MySQL InnoDB: Repeatable Read (which is their name for snapshot isolation).

---

## Common Traps (Don't Say This in Interviews!)

1. ❌ **"Snapshot Isolation = Serializable"**
   ✅ Nope! Snapshot isolation still allows write skew and phantoms. Many databases even mislabel their snapshot isolation as "Serializable."

2. ❌ **"MVCC keeps versions forever"**
   ✅ A garbage collector (PostgreSQL's VACUUM) removes old versions that no active transaction needs.

3. ❌ **"SELECT FOR UPDATE prevents everything"**
   ✅ It prevents write skew on existing rows but CAN'T prevent phantoms (newly inserted rows don't exist to be locked).

4. ❌ **"Read Committed is always too weak"**
   ✅ It's the default in PostgreSQL for good reason — it's fine for 90% of OLTP workloads. Only upgrade to Snapshot/Serializable when you have multi-row invariants.

5. ❌ **"Write skew is about concurrent writes to the same row"**
   ✅ The opposite! Write skew happens when transactions write to **different** rows. That's why it's so hard to detect — there's no row-level conflict.

---
