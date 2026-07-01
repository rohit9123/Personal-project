package lld.problems.hashmap;

// ============================================================================
// 1. Linked List Node representing a Key-Value Pair
// ============================================================================
class Entry<K, V> {
    final K key;
    V value;
    Entry<K, V> next; // Reference to the next node in separate chain

    public Entry(K key, V value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public String toString() {
        return "{" + key + "=" + value + "}";
    }
}

// ============================================================================
// 2. Custom HashMap Implementation
// ============================================================================
class MyHashMap<K, V> {
    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;

    private Entry<K, V>[] buckets;
    private int capacity;
    private int size;
    private final float loadFactor;

    @SuppressWarnings("unchecked")
    public MyHashMap() {
        this.capacity = DEFAULT_INITIAL_CAPACITY;
        this.loadFactor = DEFAULT_LOAD_FACTOR;
        this.buckets = new Entry[capacity];
        this.size = 0;
    }

    // Returns the total elements in the map
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    // Computes index for a given key
    private int getBucketIndex(K key) {
        if (key == null) return 0; // null keys are always mapped to index 0
        return Math.abs(key.hashCode()) % capacity;
    }

    // Inserts or updates a key-value pair
    public void put(K key, V value) {
        // Check if load factor threshold is met (excluding rehashing calls)
        if (size >= capacity * loadFactor) {
            resize();
        }

        int index = getBucketIndex(key);
        Entry<K, V> head = buckets[index];

        // 1. Check if key already exists, if so update its value
        Entry<K, V> curr = head;
        while (curr != null) {
            if (curr.key == null && key == null) {
                curr.value = value;
                return;
            }
            if (curr.key != null && curr.key.equals(key)) {
                curr.value = value;
                return;
            }
            curr = curr.next;
        }

        // 2. If key does not exist, insert new Entry at the head of the chain (O(1) insert)
        Entry<K, V> newEntry = new Entry<>(key, value);
        newEntry.next = head;
        buckets[index] = newEntry;
        size++;
    }

    // Retrieves value for a given key
    public V get(K key) {
        int index = getBucketIndex(key);
        Entry<K, V> curr = buckets[index];

        // Traverse the chain at this index
        while (curr != null) {
            if (curr.key == null && key == null) {
                return curr.value;
            }
            if (curr.key != null && curr.key.equals(key)) {
                return curr.value;
            }
            curr = curr.next;
        }
        return null; // Key not found
    }

    // Removes a key-value pair, returning the deleted value or null
    public V remove(K key) {
        int index = getBucketIndex(key);
        Entry<K, V> curr = buckets[index];
        Entry<K, V> prev = null;

        // Traverse the chain keeping track of the previous node
        while (curr != null) {
            boolean matches = (curr.key == null && key == null) || (curr.key != null && curr.key.equals(key));
            
            if (matches) {
                if (prev == null) {
                    // Node is the head of the chain
                    buckets[index] = curr.next;
                } else {
                    // Node is in the middle or end
                    prev.next = curr.next;
                }
                size--;
                return curr.value;
            }
            prev = curr;
            curr = curr.next;
        }
        return null; // Key not found to delete
    }

    // Doubles the bucket array capacity and rehashes all current items
    @SuppressWarnings("unchecked")
    private void resize() {
        int oldCapacity = capacity;
        capacity = capacity * 2;
        System.out.println("[Rehash] Load threshold exceeded. Resizing capacity: " + oldCapacity + " -> " + capacity);

        Entry<K, V>[] oldBuckets = buckets;
        buckets = new Entry[capacity];
        size = 0; // Reset size to 0 as put() will increment it during re-insertion

        // Traverse old buckets and put entries into the new larger array
        for (int i = 0; i < oldCapacity; i++) {
            Entry<K, V> curr = oldBuckets[i];
            while (curr != null) {
                put(curr.key, curr.value); // Re-calculates indices using new capacity
                curr = curr.next;
            }
        }
    }

    // Print helper for debugging bucket layout
    public void printBuckets() {
        System.out.println("Current HashMap Bucket Layout:");
        for (int i = 0; i < capacity; i++) {
            if (buckets[i] != null) {
                System.out.print("  Bucket [" + i + "]: ");
                Entry<K, V> curr = buckets[i];
                while (curr != null) {
                    System.out.print(curr + " -> ");
                    curr = curr.next;
                }
                System.out.println("null");
            }
        }
    }
}

// ============================================================================
// 3. Custom Key with fixed hashCode to force hash collisions
// ============================================================================
class CollisionKey {
    private final String value;

    public CollisionKey(String value) {
        this.value = value;
    }

    @Override
    public int hashCode() {
        return 99; // Every key instance returns 99, forcing them into the same bucket index!
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        CollisionKey other = (CollisionKey) obj;
        return value.equals(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}

// ============================================================================
// 4. Test Driver
// ============================================================================
public class MyHashMapDemo {
    public static void main(String[] args) {
        System.out.println("--- Custom MyHashMap LLD Simulation ---");

        MyHashMap<Object, Object> map = new MyHashMap<>();

        // ==========================================
        // Test Case 1: Basic Put and Get Operations
        // ==========================================
        System.out.println("\n===== TEST CASE 1: Basic Operations =====");
        map.put("Alice", 25);
        map.put("Bob", 30);
        map.put("Charlie", 22);

        System.out.println("Get 'Alice': " + map.get("Alice")); // 25
        System.out.println("Get 'Bob': " + map.get("Bob"));     // 30
        System.out.println("Map Size: " + map.size());           // 3

        // Test update on existing key
        map.put("Alice", 28);
        System.out.println("Get updated 'Alice': " + map.get("Alice")); // 28
        System.out.println("Map Size after update: " + map.size());     // 3 (no size increase)

        // ==========================================
        // Test Case 2: Collision Handling (Separate Chaining)
        // ==========================================
        System.out.println("\n===== TEST CASE 2: Force Collision Handling =====");
        CollisionKey key1 = new CollisionKey("KeyOne");
        CollisionKey key2 = new CollisionKey("KeyTwo");
        CollisionKey key3 = new CollisionKey("KeyThree");

        map.put(key1, "Apple");
        map.put(key2, "Banana");
        map.put(key3, "Cherry");

        // Verify all colliding keys can be retrieved successfully
        System.out.println("Get 'KeyOne': " + map.get(key1));     // Apple
        System.out.println("Get 'KeyTwo': " + map.get(key2));     // Banana
        System.out.println("Get 'KeyThree': " + map.get(key3));   // Cherry
        
        // Print buckets to see the linked list chain
        map.printBuckets();

        // ==========================================
        // Test Case 3: Removal Operations
        // ==========================================
        System.out.println("\n===== TEST CASE 3: Node Removal =====");
        System.out.println("Remove 'KeyTwo' (Middle of chain): " + map.remove(key2)); // Banana
        System.out.println("Get 'KeyTwo' after deletion: " + map.get(key2));        // null
        System.out.println("Get 'KeyOne' after deletion: " + map.get(key1));        // Apple (still intact)
        System.out.println("Get 'KeyThree' after deletion: " + map.get(key3));      // Cherry (still intact)

        map.printBuckets();

        // ==========================================
        // Test Case 4: Dynamic Rehashing (Resizing)
        // ==========================================
        System.out.println("\n===== TEST CASE 4: Dynamic Rehashing =====");
        System.out.println("Current Map Size: " + map.size()); // Currently 5 elements
        
        // Let's add more items to trigger capacity resize from 16 to 32
        // Threshold = 16 * 0.75 = 12. Adding up to 13 items.
        for (int i = 1; i <= 10; i++) {
            map.put("ExtraKey_" + i, 100 + i);
        }

        System.out.println("\nFinal Map Size: " + map.size()); // Should be 15 elements
        System.out.println("Get 'ExtraKey_5': " + map.get("ExtraKey_5")); // 105
        System.out.println("Get 'Alice': " + map.get("Alice")); // 28 (original data preserved)
        System.out.println("Get 'KeyOne': " + map.get(key1));   // Apple (original data preserved)
    }
}
