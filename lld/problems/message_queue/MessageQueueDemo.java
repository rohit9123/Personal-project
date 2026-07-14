import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MessageQueueDemo {

    // ==============================================
    // 1. Core Data Models & Entities
    // ==============================================

    public static class Message {
        private final String key;
        private final String value;
        private final long offset;
        private final long timestamp;

        public Message(String key, String value, long offset, long timestamp) {
            this.key = key;
            this.value = value;
            this.offset = offset;
            this.timestamp = timestamp;
        }

        public String getKey() { return key; }
        public String getValue() { return value; }
        public long getOffset() { return offset; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("Message{offset=%d, key='%s', val='%s'}", offset, key, value);
        }
    }

    public static class TopicPartition {
        private final String topic;
        private final int partition;

        public TopicPartition(String topic, int partition) {
            this.topic = topic;
            this.partition = partition;
        }

        public String getTopic() { return topic; }
        public int getPartition() { return partition; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TopicPartition that = (TopicPartition) o;
            return partition == that.partition && Objects.equals(topic, that.topic);
        }

        @Override
        public int hashCode() {
            return Objects.hash(topic, partition);
        }

        @Override
        public String toString() {
            return topic + "-P" + partition;
        }
    }

    public static class GroupTopicPartition {
        private final String groupId;
        private final String topic;
        private final int partition;

        public GroupTopicPartition(String groupId, String topic, int partition) {
            this.groupId = groupId;
            this.topic = topic;
            this.partition = partition;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GroupTopicPartition that = (GroupTopicPartition) o;
            return partition == that.partition &&
                    Objects.equals(groupId, that.groupId) &&
                    Objects.equals(topic, that.topic);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, topic, partition);
        }

        @Override
        public String toString() {
            return String.format("%s:%s-P%d", groupId, topic, partition);
        }
    }

    // ==============================================
    // 2. Storage & Partitioning Layers
    // ==============================================

    public static class Partition {
        private final int partitionId;
        private final List<Message> messages = new ArrayList<>();
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

        public Partition(int partitionId) {
            this.partitionId = partitionId;
        }

        public int getPartitionId() {
            return partitionId;
        }

        public long append(String key, String value) {
            rwLock.writeLock().lock();
            try {
                long offset = messages.size();
                Message msg = new Message(key, value, offset, System.currentTimeMillis());
                messages.add(msg);
                return offset;
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        public List<Message> read(long startOffset, int maxMessages) {
            if (startOffset < 0) {
                throw new IllegalArgumentException("Start offset cannot be negative.");
            }
            if (maxMessages < 0) {
                throw new IllegalArgumentException("Maximum messages cannot be negative.");
            }
            rwLock.readLock().lock();
            try {
                if (startOffset >= messages.size()) {
                    return Collections.emptyList();
                }
                int start = (int) startOffset;
                int end = Math.min(start + maxMessages, messages.size());
                return new ArrayList<>(messages.subList(start, end));
            } finally {
                rwLock.readLock().unlock();
            }
        }
    }

    public static class Topic {
        private final String name;
        private final List<Partition> partitions;

        public Topic(String name, int numPartitions) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Topic name cannot be blank.");
            }
            if (numPartitions <= 0) {
                throw new IllegalArgumentException("A topic must have at least one partition.");
            }
            this.name = name;
            List<Partition> temp = new ArrayList<>();
            for (int i = 0; i < numPartitions; i++) {
                temp.add(new Partition(i));
            }
            this.partitions = Collections.unmodifiableList(temp);
        }

        public String getName() { return name; }
        public List<Partition> getPartitions() { return partitions; }
        public Partition getPartition(int id) {
            if (id < 0 || id >= partitions.size()) {
                throw new IllegalArgumentException("Invalid partition id: " + id);
            }
            return partitions.get(id);
        }
        public int getPartitionCount() { return partitions.size(); }
    }

    public interface Partitioner {
        int getPartition(String topic, String key, int numPartitions);
    }

    public static class DefaultPartitioner implements Partitioner {
        private final ConcurrentHashMap<String, AtomicInteger> topicCounters = new ConcurrentHashMap<>();

        @Override
        public int getPartition(String topic, String key, int numPartitions) {
            if (key == null) {
                // Round-Robin across partitions for null keys
                AtomicInteger counter = topicCounters.computeIfAbsent(topic, k -> new AtomicInteger(0));
                return Math.floorMod(counter.getAndIncrement(), numPartitions);
            }
            // Key-hash based partitioning
            return Math.floorMod(key.hashCode(), numPartitions);
        }
    }

    // ==============================================
    // 3. Consumer & Group Coordination Layers
    // ==============================================

    public static class Consumer {
        private final String consumerId;
        private final String groupId;
        private final Broker broker;
        private final Set<TopicPartition> assignedPartitions = ConcurrentHashMap.newKeySet();
        private final Map<TopicPartition, Long> nextFetchOffsets = new ConcurrentHashMap<>();

        public Consumer(String consumerId, String groupId, Broker broker) {
            this.consumerId = consumerId;
            this.groupId = groupId;
            this.broker = broker;
        }

        public String getConsumerId() { return consumerId; }
        public String getGroupId() { return groupId; }

        public synchronized void assign(TopicPartition tp) {
            assignedPartitions.add(tp);
            long committed = broker.getCommittedOffset(groupId, tp);
            long startOffset = committed >= 0 ? committed : 0L;
            nextFetchOffsets.put(tp, startOffset);
            System.out.printf("[Consumer-%s] Assigned partition %s, resuming from offset %d%n",
                    consumerId, tp, startOffset);
        }

        public synchronized void revoke(TopicPartition tp) {
            assignedPartitions.remove(tp);
            nextFetchOffsets.remove(tp);
            System.out.printf("[Consumer-%s] Revoked partition %s%n", consumerId, tp);
        }

        public synchronized void clearAssignments() {
            assignedPartitions.clear();
            nextFetchOffsets.clear();
        }

        public Set<TopicPartition> getAssignedPartitions() {
            return Collections.unmodifiableSet(assignedPartitions);
        }

        public Map<TopicPartition, List<Message>> poll(int maxMessagesPerPartition) {
            List<TopicPartition> partitions;
            synchronized (this) {
                partitions = new ArrayList<>(assignedPartitions);
            }

            Map<TopicPartition, List<Message>> polledData = new HashMap<>();
            for (TopicPartition tp : partitions) {
                Long fetchOffset;
                synchronized (this) {
                    if (!assignedPartitions.contains(tp)) {
                        continue; // Revoked during rebalance
                    }
                    fetchOffset = nextFetchOffsets.get(tp);
                    if (fetchOffset == null) {
                        long committed = broker.getCommittedOffset(groupId, tp);
                        fetchOffset = committed >= 0 ? committed : 0L;
                        nextFetchOffsets.put(tp, fetchOffset);
                    }
                }

                List<Message> messages = broker.readMessages(tp.getTopic(), tp.getPartition(), fetchOffset, maxMessagesPerPartition);
                if (!messages.isEmpty()) {
                    synchronized (this) {
                        // Double check ownership before updating
                        if (assignedPartitions.contains(tp)) {
                            polledData.put(tp, messages);
                            long nextOffset = messages.get(messages.size() - 1).getOffset() + 1;
                            nextFetchOffsets.put(tp, nextOffset);
                        }
                    }
                }
            }
            return polledData;
        }

        public void commit(TopicPartition tp, long offset) {
            broker.commitOffset(groupId, tp, offset);
        }

        public void commitAll() {
            Map<TopicPartition, Long> offsetsToCommit = new HashMap<>();
            synchronized (this) {
                for (TopicPartition tp : assignedPartitions) {
                    Long offset = nextFetchOffsets.get(tp);
                    if (offset != null) {
                        offsetsToCommit.put(tp, offset);
                    }
                }
            }

            for (Map.Entry<TopicPartition, Long> entry : offsetsToCommit.entrySet()) {
                broker.commitOffset(groupId, entry.getKey(), entry.getValue());
            }
        }
    }

    public static class ConsumerGroup {
        private final String groupId;
        private final Broker broker;
        private final List<Consumer> consumers = new CopyOnWriteArrayList<>();
        private final Set<String> subscribedTopics = ConcurrentHashMap.newKeySet();
        private final Map<TopicPartition, Consumer> partitionAssignment = new ConcurrentHashMap<>();

        public ConsumerGroup(String groupId, Broker broker) {
            this.groupId = groupId;
            this.broker = broker;
        }

        public String getGroupId() { return groupId; }

        public synchronized void registerConsumer(Consumer consumer) {
            if (!consumers.contains(consumer)) {
                consumers.add(consumer);
                System.out.printf("[ConsumerGroup-%s] Consumer %s registered.%n", groupId, consumer.getConsumerId());
                rebalance();
            }
        }

        public synchronized void deregisterConsumer(Consumer consumer) {
            if (consumers.remove(consumer)) {
                System.out.printf("[ConsumerGroup-%s] Consumer %s deregistered.%n", groupId, consumer.getConsumerId());
                rebalance();
            }
        }

        public synchronized void subscribeToTopic(String topicName) {
            if (subscribedTopics.add(topicName)) {
                System.out.printf("[ConsumerGroup-%s] Subscribed to topic: %s%n", groupId, topicName);
                rebalance();
            }
        }

        public synchronized void rebalance() {
            System.out.printf("[ConsumerGroup-%s] Rebalancing group... Total Consumers: %d, Subscribed Topics: %s%n",
                    groupId, consumers.size(), subscribedTopics);

            // Clear current assignments
            partitionAssignment.clear();
            for (Consumer consumer : consumers) {
                consumer.clearAssignments();
            }

            if (consumers.isEmpty()) {
                return;
            }

            // Distribute partitions of all subscribed topics among active consumers (round-robin)
            for (String topicName : subscribedTopics) {
                Topic topic = broker.getTopic(topicName);
                if (topic == null) continue;

                List<Partition> partitions = topic.getPartitions();
                int numConsumers = consumers.size();

                for (int i = 0; i < partitions.size(); i++) {
                    Partition partition = partitions.get(i);
                    Consumer consumer = consumers.get(i % numConsumers);
                    TopicPartition tp = new TopicPartition(topicName, partition.getPartitionId());
                    partitionAssignment.put(tp, consumer);
                    consumer.assign(tp);
                }
            }
        }
    }

    // ==============================================
    // 4. Broker / Orchestration Layer
    // ==============================================

    public static class Broker {
        private final ConcurrentHashMap<String, Topic> topics = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, ConsumerGroup> consumerGroups = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<GroupTopicPartition, Long> committedOffsets = new ConcurrentHashMap<>();
        private final Partitioner partitioner = new DefaultPartitioner();

        public Topic createTopic(String name, int numPartitions) {
            Topic topic = new Topic(name, numPartitions);
            if (topics.putIfAbsent(name, topic) != null) {
                throw new IllegalArgumentException("Topic " + name + " already exists.");
            }
            System.out.printf("[Broker] Created Topic: %s with %d partitions.%n", name, numPartitions);
            return topic;
        }

        public Topic getTopic(String name) {
            return topics.get(name);
        }

        public long publish(String topicName, String key, String value) {
            Topic topic = topics.get(topicName);
            if (topic == null) {
                throw new IllegalArgumentException("Topic " + topicName + " does not exist.");
            }
            int partitionId = partitioner.getPartition(topicName, key, topic.getPartitionCount());
            Partition partition = topic.getPartition(partitionId);
            long offset = partition.append(key, value);
            return offset;
        }

        public List<Message> readMessages(String topicName, int partitionId, long startOffset, int maxMessages) {
            Topic topic = topics.get(topicName);
            if (topic == null) {
                return Collections.emptyList();
            }
            Partition partition = topic.getPartition(partitionId);
            return partition.read(startOffset, maxMessages);
        }

        public ConsumerGroup getOrCreateConsumerGroup(String groupId) {
            return consumerGroups.computeIfAbsent(groupId, id -> new ConsumerGroup(id, this));
        }

        public void registerConsumer(String groupId, Consumer consumer) {
            ConsumerGroup group = getOrCreateConsumerGroup(groupId);
            group.registerConsumer(consumer);
        }

        public void deregisterConsumer(String groupId, Consumer consumer) {
            ConsumerGroup group = consumerGroups.get(groupId);
            if (group != null) {
                group.deregisterConsumer(consumer);
            }
        }

        public void subscribeGroupToTopic(String groupId, String topicName) {
            if (!topics.containsKey(topicName)) {
                throw new IllegalArgumentException("Topic " + topicName + " does not exist.");
            }
            ConsumerGroup group = getOrCreateConsumerGroup(groupId);
            group.subscribeToTopic(topicName);
        }

        public void commitOffset(String groupId, TopicPartition tp, long offset) {
            if (offset < 0) {
                throw new IllegalArgumentException("Committed offset cannot be negative.");
            }
            GroupTopicPartition gtp = new GroupTopicPartition(groupId, tp.getTopic(), tp.getPartition());
            committedOffsets.merge(gtp, offset, Math::max);
            System.out.printf("[OffsetManager] Group '%s' committed %s -> Offset %d%n", groupId, tp, offset);
        }

        public long getCommittedOffset(String groupId, TopicPartition tp) {
            GroupTopicPartition gtp = new GroupTopicPartition(groupId, tp.getTopic(), tp.getPartition());
            return committedOffsets.getOrDefault(gtp, -1L);
        }
    }

    // ==============================================
    // 5. Multi-Threaded Simulation Helpers
    // ==============================================

    public static class ConsumerWorker implements Runnable {
        private final Consumer consumer;
        private final AtomicBoolean running = new AtomicBoolean(true);

        public ConsumerWorker(Consumer consumer) {
            this.consumer = consumer;
        }

        public void stop() {
            running.set(false);
        }

        @Override
        public void run() {
            String threadName = Thread.currentThread().getName();
            System.out.printf("[%s] Consumer %s started polling loop.%n", threadName, consumer.getConsumerId());
            while (running.get()) {
                try {
                    Map<TopicPartition, List<Message>> records = consumer.poll(3);
                    boolean processedAny = false;
                    for (Map.Entry<TopicPartition, List<Message>> entry : records.entrySet()) {
                        TopicPartition tp = entry.getKey();
                        List<Message> messages = entry.getValue();
                        for (Message msg : messages) {
                            System.out.printf("[%s] Consumer %s processed from %s: Key=%s, Val=%s, Offset=%d%n",
                                    threadName, consumer.getConsumerId(), tp, msg.getKey(), msg.getValue(), msg.getOffset());
                            processedAny = true;
                        }
                    }
                    if (processedAny) {
                        consumer.commitAll();
                    }
                    Thread.sleep(200); // Polling interval
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.printf("[%s] Error in consumer %s: %s%n", threadName, consumer.getConsumerId(), e.getMessage());
                }
            }
            System.out.printf("[%s] Consumer %s polling loop stopped.%n", threadName, consumer.getConsumerId());
        }
    }

    public static class ProducerWorker implements Runnable {
        private final Broker broker;
        private final String topic;
        private final int messageCount;

        public ProducerWorker(Broker broker, String topic, int messageCount) {
            this.broker = broker;
            this.topic = topic;
            this.messageCount = messageCount;
        }

        @Override
        public void run() {
            String[] keys = {"user-1", "user-2", "user-3", null, "user-4", null};
            for (int i = 0; i < messageCount; i++) {
                try {
                    String key = keys[i % keys.length];
                    String value = "Msg-Val-" + i;
                    long offset = broker.publish(topic, key, value);
                    System.out.printf("[Producer-Thread] Sent to %s: Key=%-6s, Val=%-10s -> Assigned Offset=%d%n",
                            topic, key, value, offset);
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ==============================================
    // 6. Main Method & Multi-Threaded Simulation
    // ==============================================

    public static void main(String[] args) {
        System.out.println("=== Starting In-Memory Message Queue Demo ===");

        Broker broker = new Broker();
        String topic = "orders";

        // Create Topic with 3 Partitions
        broker.createTopic(topic, 3);

        // Setup Consumer Group "billing-group" subscribing to "orders"
        String groupId = "billing-group";
        Consumer c1 = new Consumer("C1", groupId, broker);
        Consumer c2 = new Consumer("C2", groupId, broker);

        broker.subscribeGroupToTopic(groupId, topic);
        broker.registerConsumer(groupId, c1);
        broker.registerConsumer(groupId, c2);

        // ExecutorService for parallel execution
        ExecutorService executor = Executors.newCachedThreadPool();

        ConsumerWorker worker1 = new ConsumerWorker(c1);
        ConsumerWorker worker2 = new ConsumerWorker(c2);

        executor.submit(worker1);
        executor.submit(worker2);

        // Publish initial batch of messages
        System.out.println("\n--- Spawning Producer to Publish Messages ---");
        Future<?> producerFuture = executor.submit(new ProducerWorker(broker, topic, 9));

        try {
            producerFuture.get(); // Wait for producer to finish publishing
            Thread.sleep(1500);   // Allow consumers to consume and commit
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Dynamically add a third Consumer C3 (triggering rebalance)
        System.out.println("\n--- Registering Consumer C3 Dynamically (Rebalance) ---");
        Consumer c3 = new Consumer("C3", groupId, broker);
        broker.registerConsumer(groupId, c3);
        ConsumerWorker worker3 = new ConsumerWorker(c3);
        executor.submit(worker3);

        try {
            Thread.sleep(500); // Allow assignment to settle
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Publish more messages (distributed across 3 partitions / 3 consumers)
        System.out.println("\n--- Publishing More Messages (Distributed to 3 Consumers) ---");
        producerFuture = executor.submit(new ProducerWorker(broker, topic, 6));
        try {
            producerFuture.get();
            Thread.sleep(1500);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Deregister Consumer C2 (simulating crash/leave)
        System.out.println("\n--- Deregistering Consumer C2 (Simulating Crash/Leave) ---");
        worker2.stop();
        broker.deregisterConsumer(groupId, c2);

        try {
            Thread.sleep(500); // Allow assignment to settle
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Publish more messages (C2 partitions should be taken over by C1 or C3)
        System.out.println("\n--- Publishing Messages after Consumer C2 Left ---");
        producerFuture = executor.submit(new ProducerWorker(broker, topic, 6));
        try {
            producerFuture.get();
            Thread.sleep(1500);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Start a completely new Consumer Group "analytics-group" with consumer C4
        System.out.println("\n--- Setting up Second Consumer Group 'analytics-group' ---");
        String analyticsGroupId = "analytics-group";
        Consumer c4 = new Consumer("C4", analyticsGroupId, broker);
        broker.subscribeGroupToTopic(analyticsGroupId, topic);
        broker.registerConsumer(analyticsGroupId, c4);

        ConsumerWorker worker4 = new ConsumerWorker(c4);
        executor.submit(worker4);

        // Let C4 process all historical messages from the beginning (offset 0)
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Shutdown all workers
        System.out.println("\n--- Shutting Down Demo ---");
        worker1.stop();
        worker3.stop();
        worker4.stop();

        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }

        System.out.println("\n=== In-Memory Message Queue Demo Finished ===");
    }
}
