# Event-Driven Architecture

| File | Covers |
|------|--------|
| [beginners-guide.md](./beginners-guide.md) | **Beginner Guide**: What is Event-Driven Architecture, Kafka core components (Broker, Topic, Partition, Offset, Consumer Group, Replication), Java code examples, and Common Pitfalls |
| [kafka-chapter-1.md](./kafka-chapter-1.md) | Broker, Topic, Partition, Segment Log, Index, Producer |
| [kafka-chapter-2.md](./kafka-chapter-2.md) | Consumer, Consumer Group Rules, `__consumer_offsets`, Offset Commit Strategies, Leader/Follower Replication |
| [kafka-chapter-3.md](./kafka-chapter-3.md) | Controller, KRaft vs ZooKeeper, ISR, High Watermark, `min.insync.replicas`, unclean leader election |
| [kafka-chapter-4.md](./kafka-chapter-4.md) | Producer Write Flow, Consumer Read Flow, Log Compaction, Why Kafka is Fast |
| [kafka-chapter-5.md](./kafka-chapter-5.md) | Exactly-Once Transactions, Consumer Rebalance Protocol, Schema Registry Flow, Error Handling & DLQ |
| [kafka-chapter-6.md](./kafka-chapter-6.md) | Failure Scenarios: Broker Fail, Partition Leader Fail, Follower Out of ISR, Controller Fail, Consumer Crash, Consumer Timeout, Group Coordinator Fail, Zombie Producer |
| [kafka-chapter-7.md](./kafka-chapter-7.md) | Cluster Setup (KRaft mode): Single-node dev setup, 3-node cluster (dedicated controller + brokers), config properties, operational commands |
| [kafka-chapter-8.md](./kafka-chapter-8.md) | Producer Internals: Serializer → Partitioner → RecordAccumulator → Compression → Sender Thread, reordering issue, idempotence |
| [kafka-chapter-9.md](./kafka-chapter-9.md) | Consumer Internals & Group Coordination: Discovery, JoinGroup, SyncGroup, poll() Loop, Heartbeats, Static Membership, Cooperative Rebalance |
| [kafka-chapter-10.md](./kafka-chapter-10.md) | Consumer Exception Handling & DLT: Poison Pills, Blocking vs Non-Blocking Retries, `@RetryableTopic`, `DeadLetterPublishingRecoverer`, `ErrorHandlingDeserializer` |
| [kafka-chapter-11.md](./kafka-chapter-11.md) | Consumer Advanced Configs & Edge Cases: Partition Assignment Strategies, Offset Reset, Liveness Configs, Fetch Tuning, Consumer Lag, Rebalance Storms, Interceptors |
| [kafka-chapter-12.md](./kafka-chapter-12.md) | Producer Transaction Handling: PID, Epoch, Transaction Coordinator, `__transaction_state`, 2PC Protocol, Zombie Fencing, Idempotency vs Transactions |
| [kafka-chapter-13.md](./kafka-chapter-13.md) | Consumer Transaction Handling & EOS: `read_committed` vs `read_uncommitted`, LSO, Aborted Transaction Filtering, Exactly-Once Pattern, Kafka Streams EOS v2 |
| [kafka-chapter-14.md](./kafka-chapter-14.md) | Outbox Pattern — Polling Strategy: Dual Write Problem, Outbox Table Design, Polling Publisher, Spring Boot Implementation, ShedLock, Trade-offs |
| [kafka-chapter-15.md](./kafka-chapter-15.md) | Outbox Pattern — CDC with Debezium: Change Data Capture, WAL/binlog, Debezium Connector, Outbox Event Router SMT, Exactly-Once Delivery, Polling vs CDC |
| [kafka-chapter-16.md](./kafka-chapter-16.md) | Kafka vs Alternatives & When NOT to Use It: log vs broker vs DB, Kafka vs RabbitMQ/SQS/Redis Streams/Postgres `SKIP LOCKED`/Pulsar, decision framework, when to (not) use Kafka |
| [kafka-chapter-17.md](./kafka-chapter-17.md) | Topic Design, Sizing, Retention, Security & Schema Compatibility: partition-count formula, RF/min.insync.replicas, `cleanup.policy` delete/compact, tiered storage, TLS/SASL/ACLs/quotas, BACKWARD/FORWARD/FULL compatibility & rollout order |
| [kafka-chapter-18.md](./kafka-chapter-18.md) | Kafka Streams: KStream vs KTable & stream/table duality, GlobalKTable, DSL vs Processor API, joins & co-partitioning, windowing (tumbling/hopping/sliding/session), state stores & changelog topics, tasks/threads/scaling, EOS v2, interactive queries, internal topics |
| [proactive-exception-management-avro.md](./proactive-exception-management-avro.md) | Schema Registry & Kafka Avro: Producer/Consumer flows, wire format, compatibility checks, and platform integration (Spring Boot, WebFlux, Liquibase, JWT, reactive scheduling) |

