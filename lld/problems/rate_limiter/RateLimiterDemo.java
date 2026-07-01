import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Rate Limiter — LLD Machine Coding Demo
 *
 * Patterns: Strategy (algorithms), Factory (creation), Chain of Responsibility (layering).
 * Concurrency: Lock-free CAS loops with AtomicLong/AtomicInteger.
 *
 * Compile & run:
 *   javac RateLimiterDemo.java && java RateLimiterDemo
 */
public class RateLimiterDemo {

    // ─── Result ──────────────────────────────────────────────────────────

    static class RateLimitResult {
        private final boolean allowed;
        private final int remaining;
        private final long retryAfterMs;

        RateLimitResult(boolean allowed, int remaining, long retryAfterMs) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.retryAfterMs = retryAfterMs;
        }

        boolean isAllowed() { return allowed; }
        int getRemaining() { return remaining; }
        long getRetryAfterMs() { return retryAfterMs; }

        @Override
        public String toString() {
            return allowed
                ? String.format("ALLOWED (remaining=%d)", remaining)
                : String.format("DENIED  (retryAfter=%dms)", retryAfterMs);
        }
    }

    // ─── Strategy Interface ──────────────────────────────────────────────

    interface RateLimiter {
        RateLimitResult tryAcquire(String clientId);
        String getRuleName();
    }

    // ─── Token Bucket ────────────────────────────────────────────────────

    static class TokenBucketRateLimiter implements RateLimiter {
        private final String ruleName;
        private final int maxTokens;
        private final double refillRatePerMs; // tokens per millisecond
        private final long windowMs;
        private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

        TokenBucketRateLimiter(String ruleName, int maxTokens, int refillCount, long windowMs) {
            this.ruleName = ruleName;
            this.maxTokens = maxTokens;
            this.refillRatePerMs = (double) refillCount / windowMs;
            this.windowMs = windowMs;
        }

        @Override
        public RateLimitResult tryAcquire(String clientId) {
            TokenBucket bucket = buckets.computeIfAbsent(clientId,
                k -> new TokenBucket(maxTokens, refillRatePerMs));
            return bucket.tryConsume();
        }

        @Override
        public String getRuleName() { return ruleName; }
    }

    static class TokenBucket {
        private static final long SCALE = 1000; // fixed-point precision
        private final long maxTokensScaled;
        private final double refillRatePerMs;
        private final AtomicLong tokens;
        private final AtomicLong lastRefillTime;

        TokenBucket(int maxTokens, double refillRatePerMs) {
            this.maxTokensScaled = (long) maxTokens * SCALE;
            this.refillRatePerMs = refillRatePerMs;
            this.tokens = new AtomicLong(maxTokensScaled); // start full
            this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        }

        RateLimitResult tryConsume() {
            while (true) {
                long now = System.currentTimeMillis();
                long lastRefill = lastRefillTime.get();
                long elapsed = now - lastRefill;

                long currentTokens = tokens.get();

                // Refill tokens based on elapsed time
                long refilled = currentTokens;
                if (elapsed > 0) {
                    refilled = Math.min(maxTokensScaled,
                        currentTokens + (long)(elapsed * refillRatePerMs * SCALE));
                    // Try to claim the refill
                    lastRefillTime.compareAndSet(lastRefill, now);
                }

                // Try to consume one token
                long afterConsume = refilled - SCALE;
                if (afterConsume >= 0) {
                    if (tokens.compareAndSet(currentTokens, afterConsume)) {
                        return new RateLimitResult(true, (int)(afterConsume / SCALE), 0);
                    }
                    // CAS failed — another thread acted — retry
                    continue;
                } else {
                    // Not enough tokens
                    long waitMs = (refillRatePerMs > 0)
                        ? (long)((SCALE - refilled) / (refillRatePerMs * SCALE)) + 1
                        : Long.MAX_VALUE;
                    tokens.compareAndSet(currentTokens, refilled); // update refill even on deny
                    return new RateLimitResult(false, 0, Math.max(1, waitMs));
                }
            }
        }
    }

    // ─── Sliding Window Counter ──────────────────────────────────────────

    static class SlidingWindowRateLimiter implements RateLimiter {
        private final String ruleName;
        private final int maxRequests;
        private final long windowMs;
        private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();

        SlidingWindowRateLimiter(String ruleName, int maxRequests, long windowMs) {
            this.ruleName = ruleName;
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
        }

        @Override
        public RateLimitResult tryAcquire(String clientId) {
            WindowState state = windows.computeIfAbsent(clientId, k -> new WindowState(windowMs));
            return state.tryIncrement(maxRequests, windowMs);
        }

        @Override
        public String getRuleName() { return ruleName; }
    }

    static class WindowState {
        private final AtomicInteger prevCount = new AtomicInteger(0);
        private final AtomicInteger currCount = new AtomicInteger(0);
        private final AtomicLong windowStart;

        WindowState(long windowMs) {
            this.windowStart = new AtomicLong(System.currentTimeMillis());
        }

        RateLimitResult tryIncrement(int maxRequests, long windowMs) {
            long now = System.currentTimeMillis();
            long winStart = windowStart.get();

            // Rotate window if needed
            if (now - winStart >= windowMs) {
                if (windowStart.compareAndSet(winStart, now)) {
                    prevCount.set(currCount.getAndSet(0));
                }
                winStart = windowStart.get();
            }

            // Weighted estimate
            long elapsed = now - winStart;
            double overlapFraction = Math.max(0, (windowMs - elapsed)) / (double) windowMs;
            double estimated = prevCount.get() * overlapFraction + currCount.get();

            if (estimated < maxRequests) {
                int newCount = currCount.incrementAndGet();
                estimated = prevCount.get() * overlapFraction + newCount;
                int remaining = Math.max(0, (int)(maxRequests - estimated));
                return new RateLimitResult(true, remaining, 0);
            }

            long retryAfter = windowMs - elapsed;
            return new RateLimitResult(false, 0, Math.max(1, retryAfter));
        }
    }

    // ─── Fixed Window Counter ────────────────────────────────────────────

    static class FixedWindowRateLimiter implements RateLimiter {
        private final String ruleName;
        private final int maxRequests;
        private final long windowMs;
        private final ConcurrentHashMap<String, FixedWindowState> windows = new ConcurrentHashMap<>();

        FixedWindowRateLimiter(String ruleName, int maxRequests, long windowMs) {
            this.ruleName = ruleName;
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
        }

        @Override
        public RateLimitResult tryAcquire(String clientId) {
            FixedWindowState state = windows.computeIfAbsent(clientId, k -> new FixedWindowState());
            return state.tryIncrement(maxRequests, windowMs);
        }

        @Override
        public String getRuleName() { return ruleName; }
    }

    static class FixedWindowState {
        private final AtomicInteger count = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

        RateLimitResult tryIncrement(int maxRequests, long windowMs) {
            long now = System.currentTimeMillis();
            long winStart = windowStart.get();

            // Reset if window expired
            if (now - winStart >= windowMs) {
                if (windowStart.compareAndSet(winStart, now)) {
                    count.set(0);
                }
            }

            int current = count.incrementAndGet();
            if (current <= maxRequests) {
                return new RateLimitResult(true, maxRequests - current, 0);
            }

            long retryAfter = windowMs - (now - windowStart.get());
            count.decrementAndGet(); // undo increment on denial
            return new RateLimitResult(false, 0, Math.max(1, retryAfter));
        }
    }

    // ─── Rule & Algorithm Enum ───────────────────────────────────────────

    enum Algorithm { TOKEN_BUCKET, SLIDING_WINDOW, FIXED_WINDOW }

    static class RateLimitRule {
        private final String name;
        private final Algorithm algorithm;
        private final int maxRequests;
        private final long windowMs;
        private final int burstCapacity; // only for token bucket

        RateLimitRule(String name, Algorithm algorithm, int maxRequests, long windowMs, int burstCapacity) {
            this.name = name;
            this.algorithm = algorithm;
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
            this.burstCapacity = burstCapacity;
        }

        String getName() { return name; }
        Algorithm getAlgorithm() { return algorithm; }
        int getMaxRequests() { return maxRequests; }
        long getWindowMs() { return windowMs; }
        int getBurstCapacity() { return burstCapacity > 0 ? burstCapacity : maxRequests; }
    }

    // ─── Factory ─────────────────────────────────────────────────────────

    static class RateLimiterFactory {
        static RateLimiter create(RateLimitRule rule) {
            return switch (rule.getAlgorithm()) {
                case TOKEN_BUCKET -> new TokenBucketRateLimiter(
                    rule.getName(), rule.getBurstCapacity(), rule.getMaxRequests(), rule.getWindowMs());
                case SLIDING_WINDOW -> new SlidingWindowRateLimiter(
                    rule.getName(), rule.getMaxRequests(), rule.getWindowMs());
                case FIXED_WINDOW -> new FixedWindowRateLimiter(
                    rule.getName(), rule.getMaxRequests(), rule.getWindowMs());
            };
        }
    }

    // ─── Chain of Responsibility ─────────────────────────────────────────

    static class ThrottleChain {
        private final List<RateLimiter> limiters = new ArrayList<>();

        void addLimiter(RateLimiter limiter) {
            limiters.add(limiter);
        }

        RateLimitResult tryAcquire(String clientId) {
            for (RateLimiter limiter : limiters) {
                RateLimitResult result = limiter.tryAcquire(clientId);
                if (!result.isAllowed()) {
                    System.out.printf("    [CHAIN] Denied by: %s%n", limiter.getRuleName());
                    return result;
                }
            }
            return new RateLimitResult(true, -1, 0);
        }
    }

    // ─── Demo ────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  RATE LIMITER — LLD DEMO");
        System.out.println("═══════════════════════════════════════════════════════════\n");

        demoTokenBucket();
        demoSlidingWindow();
        demoFixedWindow();
        demoFactory();
        demoChain();
        demoMultiThreaded();

        System.out.println("\n✓ All demos completed.");
    }

    // --- Demo 1: Token Bucket (allows burst, then throttles) ---
    static void demoTokenBucket() {
        System.out.println("── Demo 1: Token Bucket (5 tokens, refill 5/sec) ──");
        RateLimiter limiter = new TokenBucketRateLimiter("token-bucket", 5, 5, 1000);

        // Burst: send 7 requests instantly
        for (int i = 1; i <= 7; i++) {
            RateLimitResult r = limiter.tryAcquire("user:1");
            System.out.printf("  Request %d: %s%n", i, r);
        }
        System.out.println();
    }

    // --- Demo 2: Sliding Window Counter ---
    static void demoSlidingWindow() {
        System.out.println("── Demo 2: Sliding Window Counter (3 req / 500ms) ──");
        RateLimiter limiter = new SlidingWindowRateLimiter("sliding-window", 3, 500);

        for (int i = 1; i <= 5; i++) {
            RateLimitResult r = limiter.tryAcquire("user:2");
            System.out.printf("  Request %d: %s%n", i, r);
        }
        System.out.println();
    }

    // --- Demo 3: Fixed Window Counter ---
    static void demoFixedWindow() {
        System.out.println("── Demo 3: Fixed Window Counter (3 req / 500ms) ──");
        RateLimiter limiter = new FixedWindowRateLimiter("fixed-window", 3, 500);

        for (int i = 1; i <= 5; i++) {
            RateLimitResult r = limiter.tryAcquire("user:3");
            System.out.printf("  Request %d: %s%n", i, r);
        }
        System.out.println();
    }

    // --- Demo 4: Factory Pattern ---
    static void demoFactory() {
        System.out.println("── Demo 4: Factory — Create from Rule Config ──");

        RateLimitRule rule1 = new RateLimitRule("api-global", Algorithm.TOKEN_BUCKET, 10, 1000, 15);
        RateLimitRule rule2 = new RateLimitRule("login-endpoint", Algorithm.SLIDING_WINDOW, 5, 60000, 0);

        RateLimiter limiter1 = RateLimiterFactory.create(rule1);
        RateLimiter limiter2 = RateLimiterFactory.create(rule2);

        System.out.printf("  Created: %s (%s)%n", limiter1.getRuleName(), limiter1.getClass().getSimpleName());
        System.out.printf("  Created: %s (%s)%n", limiter2.getRuleName(), limiter2.getClass().getSimpleName());
        System.out.println();
    }

    // --- Demo 5: Chain of Responsibility ---
    static void demoChain() {
        System.out.println("── Demo 5: Chain — IP (5/sec) + User (3/sec) ──");

        ThrottleChain chain = new ThrottleChain();
        chain.addLimiter(new TokenBucketRateLimiter("ip-limit", 5, 5, 1000));
        chain.addLimiter(new TokenBucketRateLimiter("user-limit", 3, 3, 1000));

        // The user-limit (3) is stricter, so it should deny first
        for (int i = 1; i <= 5; i++) {
            RateLimitResult r = chain.tryAcquire("user:chain");
            System.out.printf("  Request %d: %s%n", i, r);
        }
        System.out.println();
    }

    // --- Demo 6: Multi-threaded Token Bucket ---
    static void demoMultiThreaded() throws Exception {
        System.out.println("── Demo 6: Multi-Threaded (10 threads, 5 tokens) ──");

        RateLimiter limiter = new TokenBucketRateLimiter("concurrent", 5, 5, 1000);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger denied = new AtomicInteger(0);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await(); // all threads start at once
                    RateLimitResult r = limiter.tryAcquire("user:concurrent");
                    if (r.isAllowed()) {
                        allowed.incrementAndGet();
                    } else {
                        denied.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "worker-" + threadId).start();
        }

        startLatch.countDown(); // fire!
        doneLatch.await();

        System.out.printf("  10 concurrent requests → Allowed: %d, Denied: %d%n", allowed.get(), denied.get());
        System.out.printf("  (Expected: ~5 allowed, ~5 denied — bucket capacity is 5)%n");
        System.out.println();
    }
}
