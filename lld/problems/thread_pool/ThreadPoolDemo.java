package thread_pool;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * Public class demonstrating and verifying the LLD design of the Custom Thread Pool.
 */
public class ThreadPoolDemo {

    /**
     * Custom Rejected Execution Exception to signal task rejection without relying on java.util.concurrent.RejectedExecutionException.
     */
    public static class CustomRejectedExecutionException extends RuntimeException {
        public CustomRejectedExecutionException(String message) {
            super(message);
        }
    }

    /**
     * Thread-safe Custom Blocking Queue built using ReentrantLock and Condition variables.
     */
    public static class CustomBlockingQueue<T> {
        private final Queue<T> queue = new LinkedList<>();
        private final int capacity;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private final Condition notFull = lock.newCondition();

        public CustomBlockingQueue(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("Queue capacity must be greater than zero");
            }
            this.capacity = capacity;
        }

        public void enqueue(T item) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (queue.size() == capacity) {
                    notFull.await();
                }
                queue.add(item);
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
        }

        public T dequeue() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (queue.isEmpty()) {
                    notEmpty.await();
                }
                T item = queue.poll();
                notFull.signal();
                return item;
            } finally {
                lock.unlock();
            }
        }

        public T poll(long timeout, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            lock.lockInterruptibly();
            try {
                while (queue.isEmpty()) {
                    if (nanos <= 0) {
                        return null;
                    }
                    nanos = notEmpty.awaitNanos(nanos);
                }
                T item = queue.poll();
                notFull.signal();
                return item;
            } finally {
                lock.unlock();
            }
        }

        public boolean offer(T item) {
            lock.lock();
            try {
                if (queue.size() == capacity) {
                    return false;
                }
                queue.add(item);
                notEmpty.signal();
                return true;
            } finally {
                lock.unlock();
            }
        }

        public T poll() {
            lock.lock();
            try {
                if (queue.isEmpty()) {
                    return null;
                }
                T item = queue.poll();
                notFull.signal();
                return item;
            } finally {
                lock.unlock();
            }
        }

        public boolean remove(T item) {
            lock.lock();
            try {
                boolean removed = queue.remove(item);
                if (removed) {
                    notFull.signal();
                }
                return removed;
            } finally {
                lock.unlock();
            }
        }

        public List<T> drainAll() {
            lock.lock();
            try {
                List<T> remaining = new ArrayList<>(queue);
                queue.clear();
                notFull.signalAll();
                return remaining;
            } finally {
                lock.unlock();
            }
        }

        public int size() {
            lock.lock();
            try {
                return queue.size();
            } finally {
                lock.unlock();
            }
        }

        public boolean isEmpty() {
            lock.lock();
            try {
                return queue.isEmpty();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Interface to define task rejection policies when the pool is saturated or shut down.
     */
    public static interface RejectionHandler {
        void rejectedExecution(Runnable task, CustomThreadPool executor);
    }

    /**
     * A Custom Thread Pool (Executor Service from Scratch) supporting dynamic worker threads,
     * keep-alive times for idle threads, and configurable rejection policies.
     */
    public static class CustomThreadPool {
        public enum State {
            RUNNING,
            SHUTDOWN,
            STOP,
            TERMINATED
        }

        private final int minThreads;
        private final int maxThreads;
        private final long keepAliveTimeMs;
        private final CustomBlockingQueue<Runnable> taskQueue;
        private final ReentrantLock mainLock = new ReentrantLock();
        private final Condition terminationCondition = mainLock.newCondition();
        private final Set<Worker> workers = ConcurrentHashMap.newKeySet();
        
        private final AtomicInteger workerCount = new AtomicInteger(0);
        private final AtomicLong workerIdGenerator = new AtomicLong(0);
        private volatile State state = State.RUNNING;
        private volatile RejectionHandler rejectionHandler;

        // Default Rejection Policies
        public static class AbortPolicy implements RejectionHandler {
            @Override
            public void rejectedExecution(Runnable task, CustomThreadPool executor) {
                throw new CustomRejectedExecutionException("Task " + task.toString() + 
                    " rejected. Thread pool is saturated [Pool size: " + executor.getPoolSize() + 
                    ", Queue size: " + executor.getQueue().size() + "]");
            }
        }

        public static class CallerRunsPolicy implements RejectionHandler {
            @Override
            public void rejectedExecution(Runnable task, CustomThreadPool executor) {
                if (executor.getState() == State.RUNNING) {
                    ThreadPoolDemo.log("RejectionHandler [CallerRuns]: Running task in caller thread.");
                    task.run();
                }
            }
        }

        public static class DiscardPolicy implements RejectionHandler {
            @Override
            public void rejectedExecution(Runnable task, CustomThreadPool executor) {
                ThreadPoolDemo.log("RejectionHandler [Discard]: Silently discarding task.");
            }
        }

        public static class DiscardOldestPolicy implements RejectionHandler {
            @Override
            public void rejectedExecution(Runnable task, CustomThreadPool executor) {
                if (executor.getState() == State.RUNNING) {
                    Runnable oldest = executor.getQueue().poll();
                    ThreadPoolDemo.log("RejectionHandler [DiscardOldest]: Discarded oldest task: " + oldest);
                    executor.execute(task);
                }
            }
        }

        public CustomThreadPool(int minThreads, int maxThreads, long keepAliveTime, TimeUnit unit, int queueCapacity) {
            if (minThreads < 0 || maxThreads <= 0 || maxThreads < minThreads || keepAliveTime < 0) {
                throw new IllegalArgumentException("Invalid thread pool configuration parameters");
            }
            this.minThreads = minThreads;
            this.maxThreads = maxThreads;
            this.keepAliveTimeMs = unit.toMillis(keepAliveTime);
            this.taskQueue = new CustomBlockingQueue<>(queueCapacity);
            this.rejectionHandler = new AbortPolicy(); // default policy
        }

        public void setRejectionHandler(RejectionHandler handler) {
            if (handler == null) {
                throw new NullPointerException("Rejection handler cannot be null");
            }
            this.rejectionHandler = handler;
        }

        public void execute(Runnable task) {
            if (task == null) {
                throw new NullPointerException("Task cannot be null");
            }

            if (state != State.RUNNING) {
                reject(task);
                return;
            }

            // 1. If worker count < minThreads, try to spawn a core worker
            int wc = workerCount.get();
            if (wc < minThreads) {
                if (addWorker(task, true)) {
                    return;
                }
                wc = workerCount.get();
            }

            // 2. If pool is running and we can successfully queue the task
            if (state == State.RUNNING && taskQueue.offer(task)) {
                int recheckWc = workerCount.get();
                if (state != State.RUNNING && taskQueue.remove(task)) {
                    reject(task);
                } else if (recheckWc == 0) {
                    // If all threads died or minThreads is 0, start a worker to drain the queue
                    addWorker(null, false);
                }
            }
            // 3. If queue is full, try to add a non-core worker
            else if (!addWorker(task, false)) {
                // Reached maxThreads limit or pool is stopping -> reject
                reject(task);
            }
        }

        private boolean addWorker(Runnable firstTask, boolean isCore) {
            while (true) {
                State s = state;
                // If shutdown, we can only add worker if queue is not empty and firstTask is null
                if (s != State.RUNNING) {
                    if (s == State.STOP || firstTask != null || taskQueue.isEmpty()) {
                        return false;
                    }
                }

                int wc = workerCount.get();
                int limit = isCore ? minThreads : maxThreads;
                if (wc >= limit) {
                    return false;
                }

                if (workerCount.compareAndSet(wc, wc + 1)) {
                    break;
                }
            }

            boolean workerStarted = false;
            boolean workerAdded = false;
            Worker w = null;
            try {
                w = new Worker(firstTask, isCore);
                final Thread t = w.thread;
                if (t != null) {
                    mainLock.lock();
                    try {
                        // Recheck state under lock
                        State s = state;
                        if (s == State.RUNNING || (s == State.SHUTDOWN && firstTask == null)) {
                            workers.add(w);
                            workerAdded = true;
                        }
                    } finally {
                        mainLock.unlock();
                    }
                    if (workerAdded) {
                        t.start();
                        workerStarted = true;
                    }
                }
            } finally {
                if (!workerStarted) {
                    addWorkerFailed(w);
                }
            }
            return workerStarted;
        }

        private void addWorkerFailed(Worker w) {
            mainLock.lock();
            try {
                if (w != null) {
                    workers.remove(w);
                }
                workerCount.decrementAndGet();
            } finally {
                mainLock.unlock();
            }
            tryTerminate();
        }

        private void runWorker(Worker w) {
            Thread wt = Thread.currentThread();
            Runnable task = w.firstTask;
            w.firstTask = null;
            boolean completedAbruptly = true;
            try {
                while (task != null || (task = getTask(w)) != null) {
                    w.workerLock.lock();
                    try {
                        beforeExecute(wt, task);
                        task.run();
                        afterExecute(task, null);
                    } catch (Throwable x) {
                        afterExecute(task, x);
                        throw x;
                    } finally {
                        task = null;
                        w.workerLock.unlock();
                    }
                }
                completedAbruptly = false;
            } finally {
                processWorkerExit(w, completedAbruptly);
            }
        }

        private Runnable getTask(Worker w) {
            boolean timedOut = false;

            while (true) {
                State s = state;
                // Check if pool is stopped, or shutdown and queue is empty
                if (s != State.RUNNING && (s == State.STOP || taskQueue.isEmpty())) {
                    workerCount.decrementAndGet();
                    return null;
                }

                int wc = workerCount.get();
                // Threads time out if they exceed minThreads
                boolean timed = wc > minThreads;

                if (wc > maxThreads || (timed && timedOut)) {
                    if (workerCount.compareAndSet(wc, wc - 1)) {
                        return null;
                    }
                    continue;
                }

                try {
                    Runnable r = timed ?
                        taskQueue.poll(keepAliveTimeMs, TimeUnit.MILLISECONDS) :
                        taskQueue.dequeue();
                    if (r != null) {
                        return r;
                    }
                    timedOut = true;
                } catch (InterruptedException retry) {
                    timedOut = false;
                }
            }
        }

        private void processWorkerExit(Worker w, boolean completedAbruptly) {
            if (completedAbruptly) {
                workerCount.decrementAndGet();
            }

            mainLock.lock();
            try {
                workers.remove(w);
            } finally {
                mainLock.unlock();
            }

            tryTerminate();

            int wc = workerCount.get();
            int min = minThreads;
            if (min == 0 && !taskQueue.isEmpty()) {
                min = 1;
            }

            if (wc < min) {
                addWorker(null, false);
            }
        }

        private void tryTerminate() {
            if (state == State.TERMINATED) {
                return;
            }
            if ((state == State.STOP || (state == State.SHUTDOWN && taskQueue.isEmpty())) && workerCount.get() == 0) {
                mainLock.lock();
                try {
                    if (state != State.TERMINATED &&
                        (state == State.STOP || (state == State.SHUTDOWN && taskQueue.isEmpty())) &&
                        workerCount.get() == 0) {
                        state = State.TERMINATED;
                        terminationCondition.signalAll();
                        ThreadPoolDemo.log("ThreadPool has terminated successfully.");
                    }
                } finally {
                    mainLock.unlock();
                }
            }
        }

        public void shutdown() {
            mainLock.lock();
            try {
                if (state == State.RUNNING) {
                    state = State.SHUTDOWN;
                }
                interruptIdleWorkers();
            } finally {
                mainLock.unlock();
            }
            tryTerminate();
        }

        public List<Runnable> shutdownNow() {
            List<Runnable> tasks;
            mainLock.lock();
            try {
                state = State.STOP;
                interruptAllWorkers();
                tasks = taskQueue.drainAll();
            } finally {
                mainLock.unlock();
            }
            tryTerminate();
            return tasks;
        }

        private void interruptIdleWorkers() {
            mainLock.lock();
            try {
                for (Worker w : workers) {
                    w.interruptIfIdle();
                }
            } finally {
                mainLock.unlock();
            }
        }

        private void interruptAllWorkers() {
            mainLock.lock();
            try {
                for (Worker w : workers) {
                    w.interruptIfStarted();
                }
            } finally {
                mainLock.unlock();
            }
        }

        private void reject(Runnable task) {
            rejectionHandler.rejectedExecution(task, this);
        }

        public int getPoolSize() {
            mainLock.lock();
            try {
                return workers.size();
            } finally {
                mainLock.unlock();
            }
        }

        public int getActiveCount() {
            int active = 0;
            mainLock.lock();
            try {
                for (Worker w : workers) {
                    if (w.workerLock.isLocked()) {
                        active++;
                    }
                }
            } finally {
                mainLock.unlock();
            }
            return active;
        }

        public State getState() {
            return state;
        }

        public CustomBlockingQueue<Runnable> getQueue() {
            return taskQueue;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            mainLock.lock();
            try {
                while (state != State.TERMINATED) {
                    if (nanos <= 0) {
                        return false;
                    }
                    nanos = terminationCondition.awaitNanos(nanos);
                }
                return true;
            } finally {
                mainLock.unlock();
            }
        }

        protected void beforeExecute(Thread t, Runnable r) {}
        protected void afterExecute(Runnable r, Throwable t) {}

        /**
         * Internal worker wrapper that runs tasks and manages thread states.
         */
        private class Worker implements Runnable {
            final Thread thread;
            Runnable firstTask;
            final boolean isCore;
            final ReentrantLock workerLock = new ReentrantLock();

            Worker(Runnable firstTask, boolean isCore) {
                this.firstTask = firstTask;
                this.isCore = isCore;
                this.thread = new Thread(this, "CustomWorker-" + workerIdGenerator.incrementAndGet());
            }

            @Override
            public void run() {
                runWorker(this);
            }

            void interruptIfStarted() {
                thread.interrupt();
            }

            void interruptIfIdle() {
                if (workerLock.tryLock()) {
                    try {
                        thread.interrupt();
                    } finally {
                        workerLock.unlock();
                    }
                }
            }
        }
    }

    public static void log(String message) {
        System.out.printf("[%tT] [%s] %s%n", new Date(), Thread.currentThread().getName(), message);
    }

    public static void main(String[] args) {
        log("=== Starting Custom Thread Pool Demo ===");

        // 1. Setup Custom Thread Pool
        // Core = 2, Max = 4, Keep-Alive = 1 Second, Queue Capacity = 3
        int corePoolSize = 2;
        int maxPoolSize = 4;
        long keepAliveTime = 1;
        int queueCapacity = 3;

        CustomThreadPool pool = new CustomThreadPool(
            corePoolSize, 
            maxPoolSize, 
            keepAliveTime, 
            TimeUnit.SECONDS, 
            queueCapacity
        );

        log(String.format("Created pool: min=%d, max=%d, keepAlive=1s, queueCapacity=%d", 
            corePoolSize, maxPoolSize, queueCapacity));

        // Submit 10 tasks to test queueing, dynamic worker expansion, and rejection
        log("--- Submitting 10 tasks to the pool (AbortPolicy by default) ---");
        for (int i = 1; i <= 10; i++) {
            final int taskId = i;
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    log("Task " + taskId + " started execution.");
                    try {
                        Thread.sleep(1000); // simulate 1 second of work
                    } catch (InterruptedException e) {
                        log("Task " + taskId + " was interrupted.");
                    }
                    log("Task " + taskId + " completed execution.");
                }
                @Override
                public String toString() {
                    return "Task-" + taskId;
                }
            };

            try {
                pool.execute(task);
                log("Successfully submitted Task " + taskId + 
                    " [Pool Size: " + pool.getPoolSize() + 
                    ", Active Threads: " + pool.getActiveCount() + 
                    ", Queue Size: " + pool.getQueue().size() + "]");
            } catch (CustomRejectedExecutionException e) {
                log("Exception: " + e.getMessage());
            }

            // Sleep briefly to let worker threads start up and capture state changes
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Wait to allow all currently running and queued tasks to process
        log("--- Waiting for tasks to complete ---");
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log("--- Active workers after workload: " + pool.getActiveCount() + " (Pool Size: " + pool.getPoolSize() + ") ---");
        log("--- Waiting for idle non-core threads to time out (> 1 second) ---");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log("--- Active workers after timeout: " + pool.getActiveCount() + " (Pool Size: " + pool.getPoolSize() + ") ---");

        // 2. Demo Rejection Policies
        log("=== Testing Rejection Policies ===");

        // --- Test: CallerRunsPolicy ---
        log("--- Testing CallerRunsPolicy ---");
        CustomThreadPool callerRunsPool = new CustomThreadPool(1, 1, 1, TimeUnit.SECONDS, 1);
        callerRunsPool.setRejectionHandler(new CustomThreadPool.CallerRunsPolicy());

        for (int i = 1; i <= 4; i++) {
            final int taskId = i;
            callerRunsPool.execute(() -> {
                log("CallerRuns-Task " + taskId + " running.");
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            });
        }
        callerRunsPool.shutdown();

        // --- Test: DiscardPolicy ---
        log("--- Testing DiscardPolicy ---");
        CustomThreadPool discardPool = new CustomThreadPool(1, 1, 1, TimeUnit.SECONDS, 1);
        discardPool.setRejectionHandler(new CustomThreadPool.DiscardPolicy());

        for (int i = 1; i <= 4; i++) {
            final int taskId = i;
            discardPool.execute(() -> {
                log("Discard-Task " + taskId + " running.");
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            });
        }
        discardPool.shutdown();

        // --- Test: DiscardOldestPolicy ---
        log("--- Testing DiscardOldestPolicy ---");
        CustomThreadPool discardOldestPool = new CustomThreadPool(1, 1, 1, TimeUnit.SECONDS, 1);
        discardOldestPool.setRejectionHandler(new CustomThreadPool.DiscardOldestPolicy());

        for (int i = 1; i <= 4; i++) {
            final int taskId = i;
            discardOldestPool.execute(new Runnable() {
                @Override
                public void run() {
                    log("DiscardOldest-Task " + taskId + " running.");
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
                @Override
                public String toString() {
                    return "Task-" + taskId;
                }
            });
        }
        discardOldestPool.shutdown();

        // 3. Demo Shutdown vs ShutdownNow
        log("=== Testing shutdown() vs shutdownNow() ===");
        CustomThreadPool shutdownPool = new CustomThreadPool(2, 2, 1, TimeUnit.SECONDS, 5);
        for (int i = 1; i <= 5; i++) {
            final int taskId = i;
            shutdownPool.execute(() -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log("ShutdownPool-Task " + taskId + " interrupted during sleep.");
                }
            });
        }
        log("Calling shutdown() on shutdownPool...");
        shutdownPool.shutdown();
        try {
            boolean terminated = shutdownPool.awaitTermination(3, TimeUnit.SECONDS);
            log("ShutdownPool terminated: " + terminated);
        } catch (InterruptedException e) {
            log("Await termination interrupted.");
        }

        CustomThreadPool shutdownNowPool = new CustomThreadPool(2, 2, 1, TimeUnit.SECONDS, 5);
        for (int i = 1; i <= 5; i++) {
            final int taskId = i;
            shutdownNowPool.execute(() -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log("ShutdownNowPool-Task " + taskId + " interrupted successfully.");
                }
            });
        }
        log("Calling shutdownNow() on shutdownNowPool...");
        List<Runnable> droppedTasks = shutdownNowPool.shutdownNow();
        log("Dropped tasks count: " + droppedTasks.size());
        try {
            boolean terminated = shutdownNowPool.awaitTermination(3, TimeUnit.SECONDS);
            log("ShutdownNowPool terminated: " + terminated);
        } catch (InterruptedException e) {
            log("Await termination interrupted.");
        }

        log("=== Custom Thread Pool Demo Completed ===");
    }
}
