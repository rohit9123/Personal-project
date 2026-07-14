import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Main Demo showing multi-threaded client requests alongside dynamic server add/remove operations.
 */
public class ConsistentHashingDemo {

    /**
     * Interface representing a generic Hash Function.
     */
    public static interface HashFunction {
        long hash(String key);
    }

    /**
     * A thread-safe wrapper around Java's MessageDigest.
     * MessageDigest itself is not thread-safe. To avoid the overhead of constant allocation,
     * we use a ThreadLocal to cache instances of MessageDigest per thread.
     */
    public static class MessageDigestHashFunction implements HashFunction {
        private final String algorithm;
        private final ThreadLocal<MessageDigest> mdThreadLocal;

        public MessageDigestHashFunction(String algorithm) {
            this.algorithm = algorithm;
            this.mdThreadLocal = ThreadLocal.withInitial(() -> {
                try {
                    return MessageDigest.getInstance(algorithm);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException("Hash algorithm not found: " + algorithm, e);
                }
            });
        }

        @Override
        public long hash(String key) {
            MessageDigest md = mdThreadLocal.get();
            md.reset();
            byte[] bytes = md.digest(key.getBytes(StandardCharsets.UTF_8));
            
            // Convert the first 8 bytes of the digest into a 64-bit long
            long hashValue = 0;
            for (int i = 0; i < Math.min(bytes.length, 8); i++) {
                hashValue = (hashValue << 8) | (bytes[i] & 0xFF);
            }
            return hashValue;
        }
    }

    /**
     * Class representing a physical server node.
     */
    public static class PhysicalNode {
        private final String id;
        private final String ipAddress;
        private final int port;

        public PhysicalNode(String id, String ipAddress, int port) {
            this.id = id;
            this.ipAddress = ipAddress;
            this.port = port;
        }

        public String getId() {
            return id;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public int getPort() {
            return port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PhysicalNode that = (PhysicalNode) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public String toString() {
            return id + " (" + ipAddress + ":" + port + ")";
        }
    }

    /**
     * Class representing a virtual node (vnode) on the ring.
     * Maps back to its parent physical node.
     */
    public static class VirtualNode {
        private final PhysicalNode physicalNode;
        private final int replicaIndex;

        public VirtualNode(PhysicalNode physicalNode, int replicaIndex) {
            this.physicalNode = physicalNode;
            this.replicaIndex = replicaIndex;
        }

        public PhysicalNode getPhysicalNode() {
            return physicalNode;
        }

        public int getReplicaIndex() {
            return replicaIndex;
        }

        /**
         * Unique identifier for the virtual node, used for hashing.
         */
        public String getIdentifier() {
            return physicalNode.getId() + "#vnode-" + replicaIndex;
        }

        @Override
        public String toString() {
            return getIdentifier();
        }
    }

    /**
     * Consistent Hash Ring implementation.
     * Wraps TreeMap and secures it using ReentrantReadWriteLock.
     */
    public static class ConsistentHashRing {
        private final TreeMap<Long, VirtualNode> ring = new TreeMap<>();
        private final HashFunction hashFunction;
        private final int numberOfReplicas;
        private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final Set<PhysicalNode> physicalNodes = new HashSet<>();

        public ConsistentHashRing(HashFunction hashFunction, int numberOfReplicas) {
            this.hashFunction = hashFunction;
            this.numberOfReplicas = numberOfReplicas;
        }

        /**
         * Dynamically adds a new physical node to the ring.
         * Registers K virtual nodes for the physical node.
         */
        public void addNode(PhysicalNode node) {
            rwLock.writeLock().lock();
            try {
                if (physicalNodes.contains(node)) {
                    System.out.printf("[Ring-Write] Physical node %s is already present.%n", node.getId());
                    return;
                }
                physicalNodes.add(node);
                for (int i = 0; i < numberOfReplicas; i++) {
                    VirtualNode vnode = new VirtualNode(node, i);
                    long hash = hashFunction.hash(vnode.getIdentifier());
                    ring.put(hash, vnode);
                }
                System.out.printf("[Ring-Write] Successfully added physical node: %s with %d virtual nodes.%n", 
                    node.getId(), numberOfReplicas);
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        /**
         * Dynamically removes a physical node and all its virtual nodes from the ring.
         */
        public void removeNode(PhysicalNode node) {
            rwLock.writeLock().lock();
            try {
                if (!physicalNodes.contains(node)) {
                    System.out.printf("[Ring-Write] Physical node %s not found on the ring.%n", node.getId());
                    return;
                }
                physicalNodes.remove(node);
                for (int i = 0; i < numberOfReplicas; i++) {
                    VirtualNode vnode = new VirtualNode(node, i);
                    long hash = hashFunction.hash(vnode.getIdentifier());
                    ring.remove(hash);
                }
                System.out.printf("[Ring-Write] Successfully removed physical node: %s.%n", node.getId());
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        /**
         * Routes a request key to the nearest physical node clockwise on the ring.
         */
        public PhysicalNode routeRequest(String requestKey) {
            rwLock.readLock().lock();
            try {
                if (ring.isEmpty()) {
                    return null;
                }
                long hash = hashFunction.hash(requestKey);
                SortedMap<Long, VirtualNode> tailMap = ring.tailMap(hash);
                long nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
                return ring.get(nodeHash).getPhysicalNode();
            } finally {
                rwLock.readLock().unlock();
            }
        }

        public Set<PhysicalNode> getPhysicalNodes() {
            rwLock.readLock().lock();
            try {
                return new HashSet<>(physicalNodes);
            } finally {
                rwLock.readLock().unlock();
            }
        }

        public int getRingSize() {
            rwLock.readLock().lock();
            try {
                return ring.size();
            } finally {
                rwLock.readLock().unlock();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=====================================================");
        System.out.println("   Starting Consistent Hashing Multi-Threaded Demo   ");
        System.out.println("=====================================================");

        // 1. Initialize Ring with SHA-256 Hashing and 100 virtual nodes per physical node
        HashFunction hashFunc = new MessageDigestHashFunction("SHA-256");
        int virtualNodesPerServer = 100;
        ConsistentHashRing ring = new ConsistentHashRing(hashFunc, virtualNodesPerServer);

        // 2. Define Initial Servers
        PhysicalNode serverA = new PhysicalNode("Server_A", "192.168.1.10", 8080);
        PhysicalNode serverB = new PhysicalNode("Server_B", "192.168.1.20", 8080);
        PhysicalNode serverC = new PhysicalNode("Server_C", "192.168.1.30", 8080);

        System.out.println("\n--- Step 1: Populating initial server nodes ---");
        ring.addNode(serverA);
        ring.addNode(serverB);
        ring.addNode(serverC);

        // 3. Test initial routing distribution of 10,000 keys
        System.out.println("\n--- Step 2: Testing initial load distribution (10,000 keys) ---");
        analyzeDistribution(ring, 10000);

        // 4. Spawn concurrent read/write workloads to simulate live server changes while serving requests
        System.out.println("\n--- Step 3: Simulating live request routing under concurrent node changes ---");
        
        ExecutorService threadPool = Executors.newFixedThreadPool(6);
        AtomicInteger successfulRoutes = new AtomicInteger(0);
        AtomicInteger failedRoutes = new AtomicInteger(0);
        AtomicBoolean running = new AtomicBoolean(true);

        // Spawn 4 client threads continuously routing requests
        for (int i = 0; i < 4; i++) {
            final int clientId = i;
            threadPool.submit(() -> {
                int count = 0;
                while (running.get()) {
                    String requestKey = "user-req-" + ThreadLocalRandom.current().nextInt(1000000);
                    PhysicalNode node = ring.routeRequest(requestKey);
                    if (node != null) {
                        successfulRoutes.incrementAndGet();
                    } else {
                        failedRoutes.incrementAndGet();
                    }
                    count++;
                    // Introduce a tiny sleep to avoid CPU starvation
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                System.out.printf("[Reader-Thread-%d] Stopped after routing %d requests.%n", clientId, count);
            });
        }

        // Spawn a writer sequence to simulate topology changes
        // Thread 1: Add a server after 1 second
        threadPool.submit(() -> {
            try {
                Thread.sleep(1000);
                PhysicalNode serverD = new PhysicalNode("Server_D", "192.168.1.40", 8080);
                System.out.println("\n>>> [Topology Change] Spawning new server: Server_D");
                ring.addNode(serverD);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 2: Remove a server after 2 seconds
        threadPool.submit(() -> {
            try {
                Thread.sleep(2000);
                System.out.println("\n>>> [Topology Change] Decommissioning server: Server_B");
                ring.removeNode(serverB);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Let the simulation run for 3 seconds
        Thread.sleep(3000);
        running.set(false); // Signal reader threads to stop
        
        threadPool.shutdown();
        if (!threadPool.awaitTermination(2, TimeUnit.SECONDS)) {
            threadPool.shutdownNow();
        }

        System.out.println("\n--- Step 4: Simulation finished ---");
        System.out.println("Total Successful Requests Routed: " + successfulRoutes.get());
        System.out.println("Total Failed Requests Routed: " + failedRoutes.get());

        // 5. Test final distribution
        System.out.println("\n--- Step 5: Testing final load distribution after topology changes (10,000 keys) ---");
        analyzeDistribution(ring, 10000);
        
        System.out.println("=====================================================");
        System.out.println("               Demo Completed Successfully          ");
        System.out.println("=====================================================");
    }

    private static void analyzeDistribution(ConsistentHashRing ring, int totalKeys) {
        Map<String, Integer> distribution = new HashMap<>();
        // Initialize counts
        for (PhysicalNode node : ring.getPhysicalNodes()) {
            distribution.put(node.getId(), 0);
        }

        // Route keys
        for (int i = 0; i < totalKeys; i++) {
            String key = "test-key-" + i;
            PhysicalNode target = ring.routeRequest(key);
            if (target != null) {
                distribution.put(target.getId(), distribution.getOrDefault(target.getId(), 0) + 1);
            }
        }

        // Print distribution stats
        System.out.printf("Ring Size (Total VNodes): %d%n", ring.getRingSize());
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            double percent = (entry.getValue() * 100.0) / totalKeys;
            System.out.printf("  - Node %s: %d keys (%.2f%%)%n", entry.getKey(), entry.getValue(), percent);
        }

        // Calculate variance/standard deviation to show uniformity
        if (distribution.size() > 1) {
            double mean = (double) totalKeys / distribution.size();
            double temp = 0;
            for (int count : distribution.values()) {
                temp += (count - mean) * (count - mean);
            }
            double variance = temp / distribution.size();
            double stdDev = Math.sqrt(variance);
            double cv = (stdDev / mean) * 100.0; // Coefficient of Variation
            System.out.printf("Distribution Uniformity: Standard Deviation = %.2f keys, Coefficient of Variation = %.2f%%%n", stdDev, cv);
        }
    }
}
