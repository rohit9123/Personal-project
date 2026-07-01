# Java Scheduler — ScheduledThreadPoolExecutor

---

## 1. What

A **Scheduler** runs tasks at a future point in time or repeatedly at a fixed cadence, without the caller blocking or managing sleep/wait loops manually.

Java's primary scheduler is `ScheduledThreadPoolExecutor` (STPE), which extends `ThreadPoolExecutor` and replaces its unbounded work queue with a **delay-based priority queue** (`DelayedWorkQueue`).

| API | Behaviour |
|-----|-----------|
| `schedule(task, delay, unit)` | Run once after `delay` |
| `scheduleAtFixedRate(task, initialDelay, period, unit)` | Run repeatedly; next fire = previous *start* + period |
| `scheduleWithFixedDelay(task, initialDelay, delay, unit)` | Run repeatedly; next fire = previous *end* + delay |

---

## 2. Why

| Problem | Without scheduler |
|---------|-------------------|
| Retry after N seconds | `Thread.sleep()` blocks a thread for the entire duration |
| Periodic health-check | Manual while-loop + sleep; exceptions crash the loop forever |
| Lease renewal | Same thread-blocking problem |
| Timeout enforcement | Need a separate watcher thread per request |

A thread pool scheduler solves all of these with a single pool shared across many tasks — threads are only consumed when a task is actually running.

---

## 3. How

### 3.1 One-Time Scheduling

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

scheduler.schedule(
    () -> System.out.println("Runs once after 3s  — thread: " + Thread.currentThread().getName()),
    3, TimeUnit.SECONDS
);
```

The returned `ScheduledFuture` lets you cancel the pending task or check whether it completed.

---

### 3.2 Fixed Rate (`scheduleAtFixedRate`)

Next execution start = **previous start** + period.

```
period = 5s, task duration = 2s

 start  finish
  |──── 2s ────|
  t=0          t=2
               |── 3s gap ──|
                            t=5  ← next start (0 + 5)
                            |──── 2s ────|
                                        t=7
                                        |── 3s gap ──|
                                                    t=10 ← next start
```

```java
scheduler.scheduleAtFixedRate(
    () -> System.out.println("Fixed-rate tick at " + Instant.now()),
    0,   // initial delay
    5,   // period
    TimeUnit.SECONDS
);
```

**Watch out:** if the task takes *longer* than the period (e.g., 7s task, 5s period) the next execution is fired *immediately* after the current one finishes — no overlap, but the schedule drifts.

---

### 3.3 Fixed Delay (`scheduleWithFixedDelay`)

Next execution start = **previous end** + delay.

```
delay = 5s, task duration = 2s

 start  finish
  |──── 2s ────|
  t=0          t=2
               |────── 5s delay ──────|
                                      t=7  ← next start (2 + 5)
                                      |──── 2s ────|
                                                  t=9
                                                  |────── 5s delay ──────|
                                                                         t=14
```

```java
scheduler.scheduleWithFixedDelay(
    () -> System.out.println("Fixed-delay tick at " + Instant.now()),
    0,   // initial delay
    5,   // delay after completion
    TimeUnit.SECONDS
);
```

Use `scheduleWithFixedDelay` when the next run should wait until the previous one is fully done (e.g., polling an external API where overlap would cause double-processing).

---

## 4. Internal Mechanics of `DelayedWorkQueue`

### Data structure

`DelayedWorkQueue` is a **min-heap** (binary heap) ordered by `scheduledTime`. Each element is a `RunnableScheduledFuture` that implements `Delayed`.

```
Heap (min by scheduledTime):

         [t=100ms]
        /          \
   [t=200ms]    [t=500ms]
   /     \
[t=300ms] [t=250ms]
```

Insertion: O(log n). Peek at the soonest task: O(1).

### How threads consume tasks

```java
// Simplified logic inside DelayedWorkQueue.take()
while (true) {
    RunnableScheduledFuture<?> head = heap.peek();
    if (head == null) {
        available.await();          // queue empty — wait unconditionally
    } else {
        long delay = head.getDelay(NANOSECONDS);
        if (delay <= 0) {
            return heap.poll();     // task is due — take it
        }
        available.awaitNanos(delay); // wait until the head task is due
    }
}
```

Every worker thread loops here. When `awaitNanos` returns, the thread checks whether the task is ready and either takes it or re-waits.

---

## 5. Why the Naive Approach Is Not Optimized

### The thundering-herd problem

With N worker threads all calling `awaitNanos(headDelay)`:

```
Time 0ms:  Task A due at t=100ms inserted.
           Thread-1 wakes, sees delay=100ms → awaitNanos(100ms)
           Thread-2 wakes, sees delay=100ms → awaitNanos(100ms)
           Thread-3 wakes, sees delay=100ms → awaitNanos(100ms)

Time 100ms: ALL three threads wake up simultaneously.
            Each tries to lock and poll the heap.
            Only one wins; the other two re-enter the loop.
            → 2 useless context switches per task.
```

Cost: O(N) wakeups per task instead of O(1). With a large thread pool and many short-interval tasks this becomes significant.

### New-head insertion race

If a new task with a shorter deadline is inserted while threads are sleeping, one thread must be interrupted to re-evaluate the head delay. Without coordination, all threads may re-wake unnecessarily.

---

## 6. Leader-Follower Optimization

### Idea

At any moment, designate exactly **one** thread as the **leader**. Only the leader does a *timed* wait on the head task. All other (follower) threads wait *indefinitely* on a condition variable. When the leader picks up a task, it signals one follower to become the new leader before returning.

### State machine

```
             task ready
Leader ──────────────────► picks up task
  │                              │
  │ timed wait                   │ signals one follower
  │ (awaitNanos)                 ▼
  │                        Follower promoted → new Leader
  │
Follower ──── unconditional await ────► woken by leader signal → becomes Leader
```

### Java's actual implementation (simplified from `DelayedWorkQueue`)

```java
private Thread leader = null;                    // the designated leader thread
private final Condition available = lock.newCondition();

RunnableScheduledFuture<?> take() throws InterruptedException {
    lock.lockInterruptibly();
    try {
        for (;;) {
            RunnableScheduledFuture<?> first = heap.peek();

            if (first == null) {
                available.await();               // empty queue — wait forever
            } else {
                long delay = first.getDelay(NANOSECONDS);

                if (delay <= 0)
                    return heap.poll();          // task is due now

                if (leader != null) {
                    available.await();           // another thread is leader — follower waits indefinitely
                } else {
                    Thread thisThread = Thread.currentThread();
                    leader = thisThread;         // elect ourselves as leader
                    try {
                        available.awaitNanos(delay); // only we do the timed wait
                    } finally {
                        if (leader == thisThread)
                            leader = null;       // resign leadership
                    }
                }
            }
        }
    } finally {
        if (leader == null && heap.peek() != null)
            available.signal();                  // wake one follower to become leader
        lock.unlock();
    }
}
```

### On `offer()` (new task inserted)

```java
boolean offer(Runnable x) {
    lock.lock();
    try {
        heap.add(e);
        if (heap.peek() == e) {      // new task is the new head (shortest delay)
            leader = null;           // invalidate current leader's timed wait
            available.signal();      // wake one thread to re-elect a leader with the new shorter delay
        }
        return true;
    } finally {
        lock.unlock();
    }
}
```

### Result

| Without Leader-Follower | With Leader-Follower |
|------------------------|----------------------|
| All N threads do `awaitNanos` | Only 1 thread does `awaitNanos` |
| N wakeups per task | 1 wakeup per task |
| N-1 wasted context switches | 0 wasted context switches |
| Lock contention on every wakeup | Minimal contention |

---

## 7. Full Runnable Demo

```java
import java.time.Instant;
import java.util.concurrent.*;

public class SchedulerDemo {

    public static void main(String[] args) throws InterruptedException {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

        // --- One-time ---
        scheduler.schedule(
            () -> log("one-time"),
            1, TimeUnit.SECONDS
        );

        // --- Fixed rate ---
        ScheduledFuture<?> fixedRate = scheduler.scheduleAtFixedRate(
            () -> {
                log("fixed-rate start");
                sleep(2); // task takes 2s, period is 3s → 1s gap between runs
                log("fixed-rate end");
            },
            0, 3, TimeUnit.SECONDS
        );

        // --- Fixed delay ---
        ScheduledFuture<?> fixedDelay = scheduler.scheduleWithFixedDelay(
            () -> {
                log("fixed-delay start");
                sleep(2); // task takes 2s, delay is 3s → 5s between starts
                log("fixed-delay end");
            },
            0, 3, TimeUnit.SECONDS
        );

        Thread.sleep(15_000);
        fixedRate.cancel(false);
        fixedDelay.cancel(false);
        scheduler.shutdown();
    }

    private static void log(String msg) {
        System.out.printf("[%s] [%s] %s%n",
            Instant.now(), Thread.currentThread().getName(), msg);
    }

    private static void sleep(long seconds) {
        try { Thread.sleep(seconds * 1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

---

## 8. Spring Boot Integration

Spring wraps STPE behind `@Scheduled` — enable with `@EnableScheduling` on a config class.

```java
@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("scheduler-");
        s.initialize();
        return s;
    }
}
```

```java
@Component
public class HealthCheckTask {

    // Fixed rate — every 10s from the previous *start*
    @Scheduled(fixedRate = 10_000)
    public void checkUpstream() {
        System.out.println("Pinging upstream at " + Instant.now());
    }

    // Fixed delay — 5s after the previous *end*
    @Scheduled(fixedDelay = 5_000)
    public void pollInbox() {
        System.out.println("Polling inbox at " + Instant.now());
    }

    // Cron — every day at 02:00
    @Scheduled(cron = "0 0 2 * * *")
    public void nightly() {
        System.out.println("Nightly job at " + Instant.now());
    }
}
```

---

## 9. Interview Angles

**Q: Difference between `scheduleAtFixedRate` and `scheduleWithFixedDelay`?**
Fixed-rate anchors the next start to the previous *start* (clock-based). Fixed-delay anchors to the previous *end* (completion-based). If your task can overlap or take variable time, use fixed-delay to prevent pile-up.

**Q: What data structure backs `ScheduledThreadPoolExecutor`?**
A min-heap (`DelayedWorkQueue`) ordered by scheduled time. Insertion is O(log n), peeking the soonest task is O(1).

**Q: What is the thundering-herd problem in naive schedulers?**
When the head task becomes ready, all N waiting threads wake up simultaneously, compete for the lock, and only one wins — the rest do useless context switches. With a large pool and high task throughput, this degrades to O(N) wakeups per task.

**Q: How does the Leader-Follower pattern fix it?**
Exactly one thread (leader) performs a timed wait equal to the head task's remaining delay. All other threads (followers) wait indefinitely. When the leader picks up the task, it signals one follower to become the new leader. Result: 1 wakeup per task regardless of pool size.

**Q: What happens when a task with a shorter deadline is inserted while threads are sleeping?**
`offer()` checks if the new element became the heap head. If so, it resets `leader = null` and signals the condition, causing one thread to re-elect itself as leader with the corrected (shorter) timed wait.

**Q: What happens if a `scheduleAtFixedRate` task takes longer than its period?**
The next execution is triggered immediately after the current one finishes (no overlap). The schedule effectively compresses — later runs happen back-to-back until the backlog is cleared.

**Q: If a task scheduled via `scheduleAtFixedRate(task, 0, 5, SECONDS)` takes 12 seconds to complete, and the pool size is 10, will multiple instances of the task run in parallel?**
**No.** Even if the thread pool has idle threads, a single periodic task will **never** execute concurrently with itself. The STPE calculates the next execution time and puts the task back into the `DelayedWorkQueue` only *after* the current run completes.

**Q: What happens if the system time jumps backwards (e.g. via NTP sync) while tasks are running in STPE?**
The STPE uses `System.nanoTime()` internally, which is a **monotonic** time source. It measures elapsed time since an arbitrary fixed point, entirely independent of the wall-clock. Thus, NTP jumps do not impact STPE delays. In contrast, Spring's `@Scheduled(cron = ...)` or Unix `cron` utilize wall-clock time and can fire duplicate tasks or skip runs.

**Q: Can `scheduleAtFixedRate` cause an OutOfMemoryError if tasks take too long and pile up?**
No. Unlike naive schedulers, STPE does not pre-populate the queue with all future execution timestamps. It only enqueues the **very next** single run after the current one terminates. A slow task results in execution compression/delays, but never unbounded queue growth.

**Q: How do you prevent a scheduled task from silently dying?**
Wrap the task body in try-catch. STPE swallows all unchecked exceptions — the task simply stops firing with no log output if an exception escapes.

```java
scheduler.scheduleAtFixedRate(() -> {
    try {
        doWork();
    } catch (Exception e) {
        log.error("Task failed", e); // without this, task silently dies
    }
}, 0, 5, TimeUnit.SECONDS);
```

