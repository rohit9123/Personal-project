# High-Level Design (HLD) & System Design

Welcome to the System Design HLD study guide. This index contains conceptual guides, building blocks, and case studies optimized for SDE-2 interviews.

---

## 1. System Design Fundamentals
Essential architectural patterns, network protocols, databases, and resource estimation models:
* **Scaling 101**: [scaling-101.md](fundamentals/scaling-101.md) — Monolith to microservices, vertical vs horizontal scaling, replication, and partitioning.
* **Back-of-the-Envelope Estimation**: [back-of-the-envelope.md](fundamentals/back-of-the-envelope.md) — Cheatsheet for QPS, latency, data bandwidth, RAM limits, and storage calculations.
* **Network Protocols**: [protocols.md](fundamentals/protocols.md) — REST vs GraphQL vs gRPC vs WebSockets vs SSE.
* **Load Balancing**: [load-balancing.md](fundamentals/load-balancing.md) — L4 vs L7 load balancers, DNS routing, and load balancing algorithms.
* **Databases & Schema Design**: [databases.md](fundamentals/databases.md) — Relational SQL vs NoSQL, wide-column layouts, indexing strategies, and transactional constraints.
* **Database Picker (Decisions & Patterns)**: [database-picker.md](fundamentals/database-picker.md) — PACELC classifications, B-Tree vs LSM-Tree vs Columnar engine structures, and CDC sync pipelines.
* **Elasticsearch vs. PostgreSQL (Inverted Index & Scalability)**: [elasticsearch-vs-postgres-inverted-index.md](fundamentals/elasticsearch-vs-postgres-inverted-index.md) — Inverted index internals, FST & Roaring Bitmaps, search at 100M+ scale, aggregations, updates, and CQRS patterns.
* **Caching Deep Dive**: [caching.md](fundamentals/caching.md) — Eviction policies, Redis memory modeling, Cache-Aside vs Write-Through, and penetration defense.
* **Message Queues**: [message-queues.md](fundamentals/message-queues.md) — Event-driven systems, Kafka vs RabbitMQ, transactional outbox pattern, and idempotent delivery.
* **Data Partitioning**: [data-partitioning.md](fundamentals/data-partitioning.md) — Sharding strategies, horizontal vs vertical partitioning, and partition key design.
* **CAP Theorem Deep-Dive**: [cap-theorem.md](fundamentals/cap-theorem.md) — Proof intuition, CP vs AP real-world systems, PACELC daily trade-offs, consistency models (strong to eventual), and interview answer framework.
* **Replication Strategies**: [replication.md](fundamentals/replication.md) — Single-leader, multi-leader, and leaderless replication, failover, conflict resolution, quorum math, and replication lag anomalies.

---

## 2. Core System Building Blocks
Reusable architecture blocks that solve specialized, horizontal system design requirements:
* **Consistent Hashing Ring**: [consistent-hashing.md](building-blocks/consistent-hashing.md) — Stateful service distribution, gossip protocols (SWIM), ring-wrap logic, and Java TreeMap implementations.
* **Distributed Unique ID Generator**: [id-generator.md](building-blocks/id-generator.md) — Twitter Snowflake, UUID fragmentation, and database auto-increment ticket servers.
* **Rate Limiter**: [rate-limiter.md](building-blocks/rate-limiter.md) — Token Bucket, Leaky Bucket, Sliding Window Log, and sliding window counters.
* **API Gateway**: [api-gateway.md](building-blocks/api-gateway.md) — Routing, cross-cutting concerns, SSL termination, and authentication.
* **Content Delivery Network (CDN)**: [cdn.md](building-blocks/cdn.md) — Edge caching, push vs pull CDNs, dynamic content acceleration, and DNS routing.
* **Distributed Blob Storage**: [blob-storage.md](building-blocks/blob-storage.md) — S3-style block storage, replication, and cold storage lifecycles.
* **Consensus Algorithms**: [consensus.md](building-blocks/consensus.md) — Raft, Paxos, leader election, and distributed agreement protocols.
* **Service Discovery**: [service-discovery.md](building-blocks/service-discovery.md) — Client-side vs server-side discovery, Eureka/Consul/ZooKeeper/etcd registries, health checks, and Kubernetes-native discovery.
* **Distributed Locking**: [distributed-locking.md](building-blocks/distributed-locking.md) — Redis SETNX/Redlock, ZooKeeper ephemeral sequential nodes, database locks, fencing tokens, and Kleppmann's Redlock critique.

---

## 3. Case Studies & System Designs
Detailed architectural solutions for classic system design interview problems:
* **Uber (Ride-Hailing Platform)**: [uber.md](problems/uber.md) — In-memory H3 hexagonal spatial grid, map matching (Kalman + HMM), active state Redis caching, Ringpop routing, and Saga-based payment completion.
* **Twitter (News Feed)**: [problems/twitter-feed.md](problems/twitter-feed.md) — Hybrid fan-out (push/pull), pre-computed Redis Sorted Sets, dual-table sharded follower graph, Flink sliding windows with Count-Min Sketch.
* **Payment System**: [problems/payment-system.md](problems/payment-system.md) — Idempotency hashing, double-entry GAAP ledger, Saga orchestration, and PostgreSQL synchronous replication.
* **Typeahead (Search Autocomplete)**: [problems/typeahead.md](problems/typeahead.md) — Precomputed top-K tries, FlatBuffers & POSIX `mmap()` zero-deserialization, range sharding with ZooKeeper, Flink trending cache.
* **Distributed Tracing System**: [problems/distributed-tracing.md](problems/distributed-tracing.md) — Stateless collectors, consistent-hashed trace buffers, tail-based sampling engine, ClickHouse columnar storage, and client-side clock drift correction.
* **Real-Time Chat System**: [problems/chat-system.md](problems/chat-system.md) — Stateful WebSocket gateways, Cassandra message log schemas, online presence heartbeat caches, and Signal protocol E2EE encryption.
* **Notification System**: [problems/notification-system.md](problems/notification-system.md) — Push, email, SMS delivery pipeline with Redis rate limits, Cassandra time-bucketing partitions, and dead letter queue error retries.
* **URL Shortener**: [problems/url-shortener.md](problems/url-shortener.md) — Base62 encoding, Key Generation Service (KGS) collision prevention, DynamoDB schema modeling, and 301 vs 302 redirect analytics.
* **BookMyShow (Ticket Booking)**: [problems/bookmyshow.md](problems/bookmyshow.md) — High-concurrency seat-locking mechanisms, relational database schemas, and async hold release patterns.
* **Google Sheets**: [problems/google-sheets.md](problems/google-sheets.md) — Operational Transformation (OT) for collaborative editing, graph-based cell dependencies, and Kahn's topological sort recalculation.
* **Google Drive (File Storage)**: [problems/google-drive.md](problems/google-drive.md) — Sync protocols, delta-sync block compression, metadata sharding, and optimistic lock collision handling.
* **Live Streaming**: [problems/live-streaming.md](problems/live-streaming.md) — Ingest pipelines, adaptive bitrate streaming, CDN fan-out, and low-latency protocols.
* **Rate Limiter (System Design)**: [problems/rate-limiter.md](problems/rate-limiter.md) — Distributed rate limiting architecture, sliding window counters, and Redis-backed throttling.
* **Distributed Key-Value Store**: [problems/key-value-store.md](problems/key-value-store.md) — Consistent hashing, quorum replication, vector clocks, gossip protocol, LSM-Tree storage, and Merkle tree anti-entropy.
* **Web Crawler**: [problems/web-crawler.md](problems/web-crawler.md) — Distributed URL frontier, Bloom Filter dedup, SimHash near-duplicate detection, per-host politeness queues, and spider trap defenses.
* **Instagram (Photo-Sharing Platform)**: [problems/instagram.md](problems/instagram.md) — Pre-signed S3 uploads, async image processing pipeline, hybrid fan-out feed, CDN-first media delivery, and ML-based feed ranking.
* **YouTube (Video Streaming Platform)**: [problems/youtube.md](problems/youtube.md) — Chunked resumable upload, DAG-based transcoding pipeline, HLS/DASH adaptive bitrate streaming, 3-tier CDN delivery, view count aggregation, and Content ID copyright system.
* **Nearby Friends (Location Sharing)**: [problems/nearby-friends.md](problems/nearby-friends.md) — Geohash spatial indexing, Redis Pub/Sub per geohash channel, WebSocket push, adaptive GPS frequency, and geofence alerts.
* **Search Engine**: [problems/search-engine.md](problems/search-engine.md) — Inverted index with compressed postings lists, BM25 + WAND early termination, two-phase ranking (BM25 → LTR re-ranking), doc-sharded scatter-gather, PageRank link analysis, and tiered index architecture.
* **Ad Click Aggregation**: [problems/ad-click-aggregation.md](problems/ad-click-aggregation.md) — Kafka-backed click ingestion, Flink tumbling-window aggregation with exactly-once semantics, Lambda Architecture (stream + batch reconciliation), click fraud detection, HyperLogLog unique counting, and ClickHouse OLAP analytics.
* **Distributed Cache (Redis/Memcached)**: [problems/distributed-cache.md](problems/distributed-cache.md) — Cache-Aside vs Write-Through vs Write-Behind strategies, Redis Cluster hash slots and gossip failover, consistent hashing, LRU/LFU eviction policies, cache stampede solutions, and hot key mitigation.
* **Pub-Sub / Message Queue System**: [problems/pub-sub-system.md](problems/pub-sub-system.md) — Kafka-style distributed commit log, partitioned topics with ISR replication, consumer groups and rebalancing protocols, offset management, log compaction, exactly-once semantics, and Kafka vs RabbitMQ vs SQS comparison.
* **Parking Lot System**: [problems/parking-lot.md](problems/parking-lot.md) — IoT sensor event streaming, Redis real-time availability counters, ALPR gate automation, TTL-based reservation holds, Edge Gateway offline resilience, and Lua-script atomic spot allocation.
* **E-Commerce Platform (Amazon-Scale)**: [problems/e-commerce.md](problems/e-commerce.md) — Polyglot persistence (PostgreSQL orders, MongoDB catalog, ElasticSearch search), Redis atomic inventory counters for flash sales, Saga-orchestrated checkout pipeline, CQRS read/write separation, and queue-based high-concurrency checkout.
* **Hotel/Accommodation Booking (Airbnb-Style)**: [problems/airbnb.md](problems/airbnb.md) — Geospatial search with ElasticSearch + Redis bitmap availability, date-range double-booking prevention via PostgreSQL exclusion constraints, split escrow payments with Stripe Connect, and dynamic pricing ML pipeline.
