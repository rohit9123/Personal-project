# Java — Topic Index

| File | Topic |
|---|---|
| [`thread-basics-part1.md`](./thread-basics-part1.md) | Thread creation (extends `Thread` vs `Runnable`) and Thread Lifecycle states (`NEW`, `RUNNABLE`, `BLOCKED`, `WAITING`, `TIMED_WAITING`, `TERMINATED`) |
| [`thread-basics-part2.md`](./thread-basics-part2.md) | Producer-Consumer coordination (`wait`/`notify`), deprecation of `stop`/`suspend`, Thread Joining (`join`), Priority, and Daemon threads |
| [`multithreading.md`](./multithreading.md) | In-depth Java Memory Model (JMM), process vs thread differences, private vs shared memory, safe data sharing mechanisms, and happens-before guarantees |
| [`cas-atomics-volatile-concurrent-collections.md`](./cas-atomics-volatile-concurrent-collections.md) | CAS (Compare-And-Swap), atomic variables, `volatile`, and concurrent collections like `ConcurrentHashMap`, `CopyOnWriteArrayList`, and `BlockingQueue` |
| [`locks-and-synchronizers.md`](./locks-and-synchronizers.md) | `ReentrantLock`, `ReadWriteLock`, `StampedLock`, `Semaphore`, and `Condition` (`await`/`signal`/`signalAll`) |
| [`thread-pools.md`](./thread-pools.md) | Thread Pool introduction, `ThreadPoolExecutor` parameters, submission logic flow, work queues, rejection policies, lifecycles, and common interview questions. |
| [`futures-and-completablefuture.md`](./futures-and-completablefuture.md) | Asynchronous task execution with `Callable`/`Future` and functional, non-blocking pipeline chaining using `CompletableFuture` (thenApply, thenCompose, thenCombine, exceptions). |
| [`threadpool-types-and-forkjoin.md`](./threadpool-types-and-forkjoin.md) | Standard Thread Pools (`Fixed`, `Cached`, `Single`) configurations and the `ForkJoinPool` Work-Stealing model using Deques (`RecursiveTask` vs `RecursiveAction`). |
| [`reflection.md`](./reflection.md) | Java Reflection API, inspection, dynamic modification, performance implications |
| [`generics-and-type-erasure.md`](./generics-and-type-erasure.md) | Generics (bounded types, wildcards, PECS), type erasure (bridge methods, restrictions), heap pollution, and generic design patterns (factory, DAO, self-bounded types) |
| [`advanced-concurrency-topics.md`](./advanced-concurrency-topics.md) | Graceful ThreadPool shutdown (`shutdown` vs `awaitTermination`), `ScheduledThreadPoolExecutor` and `ThreadLocal` memory leaks, and Java 21+ Virtual Threads vs Platform Threads |
| [`streams-and-functional.md`](./streams-and-functional.md) | Functional interfaces, lambda syntax, method references, Stream pipeline (lazy evaluation, Sink chain, Spliterator), intermediate/terminal operations, Collectors (`groupingBy`, `toMap`, custom), parallel streams (ForkJoinPool pitfalls), and `Optional` chaining |
| [`exception-handling.md`](./exception-handling.md) | Exception hierarchy (`Throwable` → `Error`/`Exception`), checked vs unchecked, try-with-resources & suppressed exceptions, custom exceptions, exception translation, Spring `@ControllerAdvice`, performance cost of `fillInStackTrace()` |

| [`collections-internals.md`](./collections-internals.md) | Java Collections internals: `HashMap` (hashing, treeification, resize), `LinkedHashMap` (LRU cache), `TreeMap` (red-black tree), `ConcurrentHashMap` (Java 7 segments vs Java 8 CAS), `HashSet`, `ArrayList` vs `LinkedList`, `PriorityQueue` (binary heap), `ArrayDeque` (circular buffer) |
| [`jvm-memory-and-gc.md`](./jvm-memory-and-gc.md) | JVM Memory Model (Heap, Stack, Metaspace, Code Cache, Direct Memory), Object Lifecycle & Generational GC, GC Roots & Reachability, Reference Types (Strong/Soft/Weak/Phantom), Garbage Collectors (Serial, Parallel, G1, ZGC, Shenandoah), Memory Leak Patterns, and Tuning/Diagnostics |
| [`modern-java-features.md`](./modern-java-features.md) | Modern Java Features (Java 10-21): Records, Sealed Classes, Pattern Matching (`instanceof` + `switch`), Text Blocks, Switch Expressions, `var`, new String/Collection/Stream APIs, and Helpful NPEs |
