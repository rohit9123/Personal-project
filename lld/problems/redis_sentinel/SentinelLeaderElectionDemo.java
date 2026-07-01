package lld.problems.redis_sentinel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLD Machine Coding Demo: Redis Sentinel Leader Election
 * 
 * This file contains a multi-threaded, asynchronous simulation of the leader election
 * protocol used by Redis Sentinel (an epoch-based Raft-like consensus algorithm).
 * 
 * Key Features:
 *  1. Epoch-based voting: First-come, first-served vote allocation per epoch.
 *  2. Quorum and Majority checks: Prevents split-brain by validating against both criteria.
 *  3. Randomized election timeouts: Avoids persistent split-vote loops.
 *  4. Network Registry: Simulates async RPCs, network delays, partitions, and node crashes.
 * 
 * To run:
 *   javac lld/problems/redis_sentinel/SentinelLeaderElectionDemo.java
 *   java lld.problems.redis_sentinel.SentinelLeaderElectionDemo
 */
public class SentinelLeaderElectionDemo {

    // ─── Enums & DTOs ──────────────────────────────────────────────────────────

    enum NodeState {
        FOLLOWER,
        CANDIDATE,
        LEADER
    }

    static class VoteRequest {
        final String candidateId;
        final long epoch;

        VoteRequest(String candidateId, long epoch) {
            this.candidateId = candidateId;
            this.epoch = epoch;
        }
    }

    static class VoteResponse {
        final String voterId;
        final long epoch;
        final boolean voteGranted;

        VoteResponse(String voterId, long epoch, boolean voteGranted) {
            this.voterId = voterId;
            this.epoch = epoch;
            this.voteGranted = voteGranted;
        }
    }

    // ─── Network Registry (RPC Simulator) ───────────────────────────────────

    static class NetworkRegistry {
        private final Map<String, SentinelNode> nodes = new ConcurrentHashMap<>();
        private final ScheduledExecutorService delayExecutor = Executors.newScheduledThreadPool(8);
        
        // Partition simulation
        private volatile boolean isPartitioned = false;
        private final Set<String> partitionA = ConcurrentHashMap.newKeySet();
        private final Set<String> partitionB = ConcurrentHashMap.newKeySet();

        void register(SentinelNode node) {
            nodes.put(node.getNodeId(), node);
        }

        List<String> getPeerIds(String selfId) {
            List<String> peers = new ArrayList<>();
            for (String id : nodes.keySet()) {
                if (!id.equals(selfId)) {
                    peers.add(id);
                }
            }
            return peers;
        }

        int getTotalNodesCount() {
            return nodes.size();
        }

        void setPartition(Set<String> partA, Set<String> partB) {
            this.partitionA.clear();
            this.partitionA.addAll(partA);
            this.partitionB.clear();
            this.partitionB.addAll(partB);
            this.isPartitioned = true;
        }

        void healPartition() {
            this.isPartitioned = false;
            this.partitionA.clear();
            this.partitionB.clear();
        }

        CompletableFuture<VoteResponse> sendVoteRequest(String senderId, String targetId, VoteRequest request) {
            CompletableFuture<VoteResponse> future = new CompletableFuture<>();

            // 1. Simulate Network Partition Drop
            if (isPartitioned) {
                boolean senderInA = partitionA.contains(senderId);
                boolean targetInA = partitionA.contains(targetId);
                boolean senderInB = partitionB.contains(senderId);
                boolean targetInB = partitionB.contains(targetId);

                if ((senderInA && targetInB) || (senderInB && targetInA)) {
                    // Simulate packet drop leading to timeout
                    delayExecutor.schedule(() -> {
                        future.completeExceptionally(new TimeoutException("Request timed out due to network partition."));
                    }, 80, TimeUnit.MILLISECONDS);
                    return future;
                }
            }

            SentinelNode targetNode = nodes.get(targetId);
            if (targetNode == null || !targetNode.isActive()) {
                delayExecutor.schedule(() -> {
                    future.completeExceptionally(new RuntimeException("Target node " + targetId + " is offline."));
                }, 5, TimeUnit.MILLISECONDS);
                return future;
            }

            // 2. Simulate Network Latency (10ms - 40ms)
            long delay = 10 + ThreadLocalRandom.current().nextLong(30);
            delayExecutor.schedule(() -> {
                try {
                    VoteResponse response = targetNode.requestVote(request.candidateId, request.epoch);
                    future.complete(response);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, delay, TimeUnit.MILLISECONDS);

            return future;
        }

        void shutdown() {
            delayExecutor.shutdownNow();
        }
    }

    // ─── Sentinel Node ────────────────────────────────────────────────────────

    static class SentinelNode {
        private final String nodeId;
        private final int quorum;
        private final NetworkRegistry registry;
        private final Object lock = new Object();

        // Node state variables
        private volatile NodeState state = NodeState.FOLLOWER;
        private final AtomicLong currentEpoch = new AtomicLong(0);
        private final Map<Long, String> votedFor = new ConcurrentHashMap<>(); // Epoch -> CandidateId
        private volatile boolean isActive = true;

        // Thread schedulers for election timeouts
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private ScheduledFuture<?> electionTimeoutTask;

        SentinelNode(String nodeId, int quorum, NetworkRegistry registry) {
            this.nodeId = nodeId;
            this.quorum = quorum;
            this.registry = registry;
        }

        String getNodeId() { return nodeId; }
        boolean isActive() { return isActive; }
        void setActive(boolean active) { this.isActive = active; }
        NodeState getState() { return state; }
        long getCurrentEpoch() { return currentEpoch.get(); }

        private void transitionTo(NodeState newState) {
            if (this.state != newState) {
                System.out.printf("   [STATE-CHANGE] %s: %s -> %s\n", nodeId, this.state, newState);
                this.state = newState;
            }
        }

        /**
         * Triggered locally when ODOWN is reached and we start failover orchestration.
         */
        void triggerODOWN() {
            synchronized (lock) {
                if (!isActive) return;
                System.out.printf("\n[ODOWN-TRIGGER] %s detected ODOWN! Starting leader election...\n", nodeId);
                startElection();
            }
        }

        /**
         * Starts a leader election for the next epoch.
         */
        private void startElection() {
            synchronized (lock) {
                if (!isActive) return;

                transitionTo(NodeState.CANDIDATE);
                long newEpoch = currentEpoch.incrementAndGet();
                
                // Vote for self first
                votedFor.put(newEpoch, nodeId);
                System.out.printf("   [ELECTION] %s: Started election for epoch %d (Self-Voted)\n", nodeId, newEpoch);

                // Schedule randomized timeout to break split-vote ties (150ms to 300ms)
                scheduleElectionTimeout();

                // Send async vote requests to peers
                List<String> peers = registry.getPeerIds(nodeId);
                int totalNodes = registry.getTotalNodesCount();
                
                AtomicInteger votesGranted = new AtomicInteger(1); // includes self-vote
                AtomicInteger responsesReceived = new AtomicInteger(1);

                for (String peerId : peers) {
                    VoteRequest request = new VoteRequest(nodeId, newEpoch);
                    registry.sendVoteRequest(nodeId, peerId, request).whenComplete((response, ex) -> {
                        if (ex != null) {
                            // Peer unreachable, offline, or request dropped
                            int total = responsesReceived.incrementAndGet();
                            checkElectionOutcome(newEpoch, votesGranted.get(), total, totalNodes);
                            return;
                        }

                        synchronized (lock) {
                            // If we discover a peer running a higher epoch, demote ourselves immediately
                            if (response.epoch > currentEpoch.get()) {
                                System.out.printf("   [STALE-EPOCH] %s: Discovered higher epoch %d on peer %s. Aborting candidacy.\n", 
                                    nodeId, response.epoch, response.voterId);
                                currentEpoch.set(response.epoch);
                                transitionTo(NodeState.FOLLOWER);
                                votedFor.clear();
                                cancelElectionTimeout();
                                return;
                            }

                            // Process vote response if we are still a candidate for the same election epoch
                            if (state == NodeState.CANDIDATE && currentEpoch.get() == newEpoch && response.epoch == newEpoch) {
                                if (response.voteGranted) {
                                    votesGranted.incrementAndGet();
                                }
                                int total = responsesReceived.incrementAndGet();
                                checkElectionOutcome(newEpoch, votesGranted.get(), total, totalNodes);
                            }
                        }
                    });
                }
            }
        }

        private void checkElectionOutcome(long epoch, int votes, int responsesCount, int totalNodesCount) {
            synchronized (lock) {
                if (state != NodeState.CANDIDATE || currentEpoch.get() != epoch) {
                    return;
                }

                int majority = (totalNodesCount / 2) + 1;

                // Candidate wins if votes >= majority AND votes >= quorum
                if (votes >= majority && votes >= quorum) {
                    transitionTo(NodeState.LEADER);
                    cancelElectionTimeout();
                    System.out.printf("\n👑 [LEADER-ELECTED] %s won election for epoch %d! Votes: %d/%d (Majority required: %d, Quorum: %d)\n",
                        nodeId, epoch, votes, totalNodesCount, majority, quorum);
                } else if (responsesCount == totalNodesCount) {
                    System.out.printf("   [ELECTION-LOST] %s: Finished election round for epoch %d. Votes secured: %d/%d. Waiting for timeout to retry.\n",
                        nodeId, epoch, votes, totalNodesCount);
                }
            }
        }

        /**
         * Processes vote requests from candidates.
         */
        VoteResponse requestVote(String candidateId, long epoch) {
            synchronized (lock) {
                if (!isActive) {
                    return new VoteResponse(nodeId, currentEpoch.get(), false);
                }

                long localEpoch = currentEpoch.get();

                // 1. Reject vote request if the candidate has an older epoch
                if (epoch < localEpoch) {
                    return new VoteResponse(nodeId, localEpoch, false);
                }

                // 2. If the candidate has a newer epoch, align ours and transition to Follower
                if (epoch > localEpoch) {
                    currentEpoch.set(epoch);
                    transitionTo(NodeState.FOLLOWER);
                    votedFor.clear();
                    cancelElectionTimeout();
                }

                // 3. First-come, first-served voting logic
                String voted = votedFor.get(epoch);
                if (voted == null) {
                    votedFor.put(epoch, candidateId);
                    System.out.printf("   [VOTE-CAST] %s voted for %s in epoch %d\n", nodeId, candidateId, epoch);
                    return new VoteResponse(nodeId, epoch, true);
                } else if (voted.equals(candidateId)) {
                    // Re-entrant/Idempotent vote confirmation
                    return new VoteResponse(nodeId, epoch, true);
                } else {
                    // Already voted for someone else in this epoch
                    System.out.printf("   [VOTE-DENIED] %s rejected %s in epoch %d (already voted for %s)\n", 
                        nodeId, candidateId, epoch, voted);
                    return new VoteResponse(nodeId, epoch, false);
                }
            }
        }

        private void scheduleElectionTimeout() {
            cancelElectionTimeout();
            long timeoutMs = 150 + ThreadLocalRandom.current().nextLong(150); // 150ms to 300ms
            long epoch = currentEpoch.get();
            
            electionTimeoutTask = scheduler.schedule(() -> {
                synchronized (lock) {
                    if (state == NodeState.CANDIDATE && currentEpoch.get() == epoch) {
                        System.out.printf("   [TIMEOUT] %s: Election timed out in epoch %d. Retrying...\n", nodeId, epoch);
                        startElection();
                    }
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
        }

        private void cancelElectionTimeout() {
            if (electionTimeoutTask != null) {
                electionTimeoutTask.cancel(false);
                electionTimeoutTask = null;
            }
        }

        void shutdown() {
            scheduler.shutdownNow();
            cancelElectionTimeout();
        }
    }

    // ─── Test Harness / Demo Scenarios ──────────────────────────────────────

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=================================================================");
        System.out.println("         REDIS SENTINEL LEADER ELECTION SIMULATION DEMO         ");
        System.out.println("=================================================================");

        runScenario1();
        runScenario2();
        runScenario3();
        runScenario4();

        System.out.println("\nAll Sentinel Leader Election scenarios completed.");
    }

    /**
     * Scenario 1: Happy Path - Single Candidate starts election, gets majority & quorum votes.
     */
    private static void runScenario1() throws InterruptedException {
        System.out.println("\n-----------------------------------------------------------------");
        System.out.println("SCENARIO 1: Happy Path (Single Candidate)");
        System.out.println("Cluster size: 5 Sentinels (S1 to S5), Quorum = 3, Majority = 3.");
        System.out.println("Expected: S1 triggers election, peers grant votes, S1 becomes Leader.");
        System.out.println("-----------------------------------------------------------------");

        NetworkRegistry registry = new NetworkRegistry();
        List<SentinelNode> cluster = createCluster(5, 3, registry);

        // S1 detects ODOWN and initiates failover
        cluster.get(0).triggerODOWN();

        // Give some time for election round to finish
        Thread.sleep(300);

        shutdownCluster(cluster, registry);
    }

    /**
     * Scenario 2: Split Vote Resolution (Concurrent Candidates)
     * S1 and S2 concurrently detect ODOWN. They compete in epoch 1, splitting votes.
     * Randomized timeouts resolve the split in epoch 2.
     */
    private static void runScenario2() throws InterruptedException {
        System.out.println("\n-----------------------------------------------------------------");
        System.out.println("SCENARIO 2: Concurrent Candidates (Split Vote Resolution)");
        System.out.println("Cluster size: 5 Sentinels, Quorum = 3.");
        System.out.println("Expected: S1 and S2 run concurrently. Neither gets majority in epoch 1.");
        System.out.println("The node with the shorter randomized timeout triggers epoch 2 first and wins.");
        System.out.println("-----------------------------------------------------------------");

        NetworkRegistry registry = new NetworkRegistry();
        List<SentinelNode> cluster = createCluster(5, 3, registry);

        // Force S1 and S2 to trigger elections simultaneously
        ExecutorService concurrentTrigger = Executors.newFixedThreadPool(2);
        concurrentTrigger.submit(() -> cluster.get(0).triggerODOWN());
        concurrentTrigger.submit(() -> cluster.get(1).triggerODOWN());

        // Wait for split-vote and resolution to complete (takes 1-2 election cycles)
        Thread.sleep(800);

        concurrentTrigger.shutdownNow();
        shutdownCluster(cluster, registry);
    }

    /**
     * Scenario 3: Network Partition (Split Brain Prevention)
     * Network splits S1, S2 from S3, S4, S5.
     * S1 runs in minority partition A. S3 runs in majority partition B.
     * Majority partition succeeds in electing leader; minority partition times out repeatedly.
     */
    private static void runScenario3() throws InterruptedException {
        System.out.println("\n-----------------------------------------------------------------");
        System.out.println("SCENARIO 3: Network Partition (Split-Brain Prevention)");
        System.out.println("Cluster split: Partition A {S1, S2} | Partition B {S3, S4, S5}.");
        System.out.println("Expected: Partition A cannot elect a leader (votes max 2/5).");
        System.out.println("Partition B successfully elects S3 (votes 3/5, quorum & majority met).");
        System.out.println("-----------------------------------------------------------------");

        NetworkRegistry registry = new NetworkRegistry();
        List<SentinelNode> cluster = createCluster(5, 3, registry);

        // Configure Partition
        Set<String> partA = new HashSet<>(Arrays.asList("S1", "S2"));
        Set<String> partB = new HashSet<>(Arrays.asList("S3", "S4", "S5"));
        registry.setPartition(partA, partB);

        System.out.println("[PARTITION] Injected network partition between {S1,S2} and {S3,S4,S5}.");

        // S1 runs in Partition A, S3 runs in Partition B
        cluster.get(0).triggerODOWN(); // S1
        cluster.get(2).triggerODOWN(); // S3

        Thread.sleep(800);

        shutdownCluster(cluster, registry);
    }

    /**
     * Scenario 4: Node Failures (Lack of Quorum/Majority)
     * Turn off 3 out of 5 sentinels. S1 tries to run, but cannot secure enough active votes.
     */
    private static void runScenario4() throws InterruptedException {
        System.out.println("\n-----------------------------------------------------------------");
        System.out.println("SCENARIO 4: Cluster Failures (Lack of Majority/Quorum)");
        System.out.println("Cluster size: 5 Sentinels, Quorum = 3. Nodes S3, S4, S5 are crashed (dead).");
        System.out.println("Expected: S1 runs for leader, but secures only 2 votes (S1, S2).");
        System.out.println("Election continues to fail & timeout as majority (3) cannot be met.");
        System.out.println("-----------------------------------------------------------------");

        NetworkRegistry registry = new NetworkRegistry();
        List<SentinelNode> cluster = createCluster(5, 3, registry);

        // Crash S3, S4, S5
        System.out.println("[CRASH] Nodes S3, S4, and S5 went offline.");
        cluster.get(2).setActive(false);
        cluster.get(3).setActive(false);
        cluster.get(4).setActive(false);

        // S1 triggers election
        cluster.get(0).triggerODOWN();

        // Let it run for 600ms to observe multiple timeout/election attempts
        Thread.sleep(600);

        shutdownCluster(cluster, registry);
    }

    // ─── Helper Methods ──────────────────────────────────────────────────────

    private static List<SentinelNode> createCluster(int size, int quorum, NetworkRegistry registry) {
        List<SentinelNode> nodes = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            SentinelNode node = new SentinelNode("S" + i, quorum, registry);
            registry.register(node);
            nodes.add(node);
        }
        return nodes;
    }

    private static void shutdownCluster(List<SentinelNode> cluster, NetworkRegistry registry) {
        registry.shutdown();
        for (SentinelNode node : cluster) {
            node.shutdown();
        }
    }
}
