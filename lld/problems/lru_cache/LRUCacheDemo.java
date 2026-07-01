package lld.problems.lru_cache;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LRU Cache — Doubly-Linked List + HashMap implementation.
 *
 * Run:
 *   javac -d . LRUCacheDemo.java
 *   java lld.problems.lru_cache.LRUCacheDemo
 */
public class LRUCacheDemo {

    // ─────────────────────────────────────────────
    // 1. Node for the Doubly-Linked List
    // ─────────────────────────────────────────────
    static class Node {
        int key, value;
        Node prev, next;

        Node(int key, int value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString() {
            return "[" + key + "=" + value + "]";
        }
    }

    // ─────────────────────────────────────────────
    // 2. Core LRU Cache (not thread-safe)
    // ─────────────────────────────────────────────
    static class LRUCache {
        private final int capacity;
        private final Map<Integer, Node> map;
        private final Node head; // sentinel — most recently used side
        private final Node tail; // sentinel — least recently used side

        LRUCache(int capacity) {
            this.capacity = capacity;
            this.map = new HashMap<>();
            this.head = new Node(0, 0); // dummy
            this.tail = new Node(0, 0); // dummy
            head.next = tail;
            tail.prev = head;
        }

        /**
         * Returns the value for the key, or -1 if not present.
         * Moves the accessed node to the head (most recently used).
         */
        int get(int key) {
            Node node = map.get(key);
            if (node == null) return -1;
            moveToHead(node);
            return node.value;
        }

        /**
         * Inserts or updates a key-value pair.
         * If at capacity, evicts the least recently used entry (tail side).
         */
        void put(int key, int value) {
            Node existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                moveToHead(existing);
            } else {
                if (map.size() == capacity) {
                    Node evicted = removeTail();
                    map.remove(evicted.key);
                }
                Node newNode = new Node(key, value);
                map.put(key, newNode);
                addToHead(newNode);
            }
        }

        int size() {
            return map.size();
        }

        // ── DLL helper methods ──

        private void addToHead(Node node) {
            node.next = head.next;
            node.prev = head;
            head.next.prev = node;
            head.next = node;
        }

        private void removeNode(Node node) {
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }

        private void moveToHead(Node node) {
            removeNode(node);
            addToHead(node);
        }

        private Node removeTail() {
            Node lru = tail.prev;
            removeNode(lru);
            return lru;
        }

        /** Returns a string showing the DLL from MRU to LRU. */
        String dllState() {
            StringBuilder sb = new StringBuilder("HEAD");
            Node cur = head.next;
            while (cur != tail) {
                sb.append(" <-> ").append(cur);
                cur = cur.next;
            }
            sb.append(" <-> TAIL");
            return sb.toString();
        }
    }

    // ─────────────────────────────────────────────
    // 3. Thread-Safe Wrapper (ReentrantReadWriteLock)
    // ─────────────────────────────────────────────
    static class ThreadSafeLRUCache {
        private final LRUCache cache;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        ThreadSafeLRUCache(int capacity) {
            this.cache = new LRUCache(capacity);
        }

        /**
         * Note: uses WRITE lock even for get(), because get() mutates
         * the DLL order (moveToHead). A read lock would allow concurrent
         * gets to corrupt the linked list pointers.
         */
        int get(int key) {
            lock.writeLock().lock();
            try {
                return cache.get(key);
            } finally {
                lock.writeLock().unlock();
            }
        }

        void put(int key, int value) {
            lock.writeLock().lock();
            try {
                cache.put(key, value);
            } finally {
                lock.writeLock().unlock();
            }
        }

        int size() {
            lock.readLock().lock();
            try {
                return cache.size();
            } finally {
                lock.readLock().unlock();
            }
        }
    }

    // ─────────────────────────────────────────────
    // 4. Test harness
    // ─────────────────────────────────────────────
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String testName, int actual, int expected) {
        if (actual == expected) {
            System.out.println("  PASS: " + testName + " => " + actual);
            passed++;
        } else {
            System.out.println("  FAIL: " + testName + " => expected " + expected + ", got " + actual);
            failed++;
        }
    }

    // ─────────────────────────────────────────────
    // 5. Main — runs all test scenarios
    // ─────────────────────────────────────────────
    public static void main(String[] args) {
        System.out.println("=== LRU Cache Demo ===\n");

        testBasicOperations();
        testEviction();
        testUpdateExistingKey();
        testLeetCode146Example();
        testThreadSafeCache();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void testBasicOperations() {
        System.out.println("[Test] Basic get/put");
        LRUCache cache = new LRUCache(2);

        cache.put(1, 10);
        cache.put(2, 20);
        check("get(1)", cache.get(1), 10);
        check("get(2)", cache.get(2), 20);
        check("get(3) miss", cache.get(3), -1);
        System.out.println("  DLL: " + cache.dllState());
        System.out.println();
    }

    static void testEviction() {
        System.out.println("[Test] Eviction — capacity=2, insert 3 items");
        LRUCache cache = new LRUCache(2);

        cache.put(1, 10);
        cache.put(2, 20);
        System.out.println("  Before eviction: " + cache.dllState());

        cache.put(3, 30); // should evict key=1 (LRU)
        System.out.println("  After put(3,30): " + cache.dllState());

        check("get(1) evicted", cache.get(1), -1);
        check("get(2) still present", cache.get(2), 20);
        check("get(3) present", cache.get(3), 30);
        System.out.println();
    }

    static void testUpdateExistingKey() {
        System.out.println("[Test] Update existing key moves it to MRU");
        LRUCache cache = new LRUCache(2);

        cache.put(1, 10);
        cache.put(2, 20);
        cache.put(1, 100); // update key=1, should become MRU
        System.out.println("  After update put(1,100): " + cache.dllState());

        cache.put(3, 30); // should evict key=2 (now LRU), NOT key=1
        check("get(2) evicted", cache.get(2), -1);
        check("get(1) updated value", cache.get(1), 100);
        check("get(3) present", cache.get(3), 30);
        System.out.println();
    }

    static void testLeetCode146Example() {
        System.out.println("[Test] LeetCode 146 example");
        LRUCache cache = new LRUCache(2);

        cache.put(1, 1);
        cache.put(2, 2);
        check("get(1)", cache.get(1), 1);

        cache.put(3, 3); // evicts key=2
        check("get(2) evicted", cache.get(2), -1);

        cache.put(4, 4); // evicts key=1
        check("get(1) evicted", cache.get(1), -1);
        check("get(3)", cache.get(3), 3);
        check("get(4)", cache.get(4), 4);
        System.out.println();
    }

    static void testThreadSafeCache() {
        System.out.println("[Test] Thread-safe LRU cache — concurrent puts");
        ThreadSafeLRUCache cache = new ThreadSafeLRUCache(100);

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 50; i++) cache.put(i, i * 10);
        });
        Thread t2 = new Thread(() -> {
            for (int i = 50; i < 100; i++) cache.put(i, i * 10);
        });

        t1.start();
        t2.start();
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        check("size after concurrent puts", cache.size(), 100);
        check("get(0)", cache.get(0), 0);
        check("get(49)", cache.get(49), 490);
        check("get(50)", cache.get(50), 500);
        check("get(99)", cache.get(99), 990);
        System.out.println();
    }
}
