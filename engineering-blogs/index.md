# High-Quality Backend Engineering Blogs for SDE-2

As an SDE-2 (Software Development Engineer II), you are expected to understand not just *how* to build something, but *why* specific architectural decisions were made, the trade-offs involved, and how systems scale in the real world. 

Reading engineering blogs from tech giants is one of the best ways to learn this. Below is a curated list of top engineering blogs and what to look out for.

## 1. Netflix Tech Blog
**URL:** https://netflixtechblog.com/
**Core Focus:** High availability, Chaos Engineering, Global Content Delivery, and Microservices.
* **Real-World Problem:** Ensuring 99.99% uptime while deploying thousands of times a day.
* **Key Concepts:** Chaos Monkey, Open Connect (Custom CDN), Zuul (API Gateway).
* **Must-Read Topics:** 
  * [Zuul 2: The Netflix Journey to Asynchronous, Non-Blocking Systems](https://netflixtechblog.com/zuul-2-the-netflix-journey-to-asynchronous-non-blocking-systems-45947377fb5c)
  * Their evolution from a monolith to microservices and how they handle resilient systems.

## 2. Uber Engineering Blog
**URL:** https://www.uber.com/blog/engineering/
**Core Focus:** Real-time distributed systems, Geospatial indexing, and massive write volumes.
* **Real-World Problem:** The "Thundering Herd" problem during peak hours and sub-second driver-rider matching.
* **Key Concepts:** Schemaless (custom datastore over MySQL), H3 (geospatial indexing), Ringpop, Adaptive Load Shedding (Cinnamon).
* **Must-Read Topics:** 
  * 🌟 **[Master Unified Guide: Uber Databases & Load Management](file:///Users/rohit.kumar.4/Documents/interview-prep/engineering-blogs/uber-database-and-load-management-master.md)** (Combined notes for all three topics)
  * [Designing Schemaless: MySQL Datastore](https://www.uber.com/blog/schemaless-part-one-mysql-datastore/) ➡️ [Our Notes (Parts 1, 2, 3 + Simple Example)](file:///Users/rohit.kumar.4/Documents/interview-prep/engineering-blogs/uber-schemaless.md)
  * [Evolving Schemaless into a Distributed SQL Database](https://www.uber.com/blog/schemaless-sql-database/) ➡️ [Our Notes](file:///Users/rohit.kumar.4/Documents/interview-prep/engineering-blogs/uber-evolving-schemaless.md)
  * [From Static Rate-Limiting to Intelligent Load Management](https://www.uber.com/blog/from-static-rate-limiting-to-intelligent-load-management/) ➡️ [Our Notes](file:///Users/rohit.kumar.4/Documents/interview-prep/engineering-blogs/uber-load-management.md)
  * How they scaled to serve millions of active riders.

## 3. Stripe Engineering Blog
**URL:** https://stripe.com/blog/engineering
**Core Focus:** API Design, Reliability, Financial Data Integrity, Infrastructure-as-Code.
* **Real-World Problem:** Moving billions of dollars with zero data loss while maintaining backward-compatible APIs.
* **Key Concepts:** Idempotency Keys (preventing double payments), API Versioning via Gateways, sophisticated Rate Limiting.
* **Must-Read Topics:** 
  * [Designing robust APIs with idempotency](https://stripe.com/blog/idempotency) (How Stripe prevents double-payments)
  * Service-to-Service Authentication.

## 4. Discord Engineering Blog
**URL:** https://discord.com/blog-tags/engineering
**Core Focus:** Real-time communication, High-performance languages (Rust/Elixir), Massive scaling.
* **Real-World Problem:** Handling 15+ million concurrent users in a single chat infrastructure.
* **Key Concepts:** Language migrations (Go to Rust to avoid GC pauses), ScyllaDB migrations.
* **Must-Read Topics:** 
  * [Why Discord is switching from Go to Rust](https://discord.com/blog/why-discord-is-switching-from-go-to-rust)
  * [How Discord Stores Trillions of Messages](https://discord.com/blog/how-discord-stores-trillions-of-messages) (The evolution of their message storage)

## 5. Airbnb Engineering & Data Science
**URL:** https://medium.com/airbnb-engineering
**Core Focus:** Service-Oriented Architecture (SOA), Data Pipelines, and Scaling Organizations.
* **Real-World Problem:** Monolithic Ruby on Rails app ("Mona Lisa") becoming a deployment bottleneck; chaotic data pipeline dependencies.
* **Key Concepts:** SOA decomposition, Directed Acyclic Graphs (DAGs) for workflows.
* **Must-Read Topics:** 
  * Their migration from a Monolith to SOA (Microservices).
  * The creation and architecture of **Apache Airflow** for programmatic data pipeline scheduling.

## 6. Slack Engineering Blog
**URL:** https://slack.engineering/
**Core Focus:** Horizontal Scaling, Real-time Messaging, and Database Sharding.
* **Real-World Problem:** MySQL instances hitting vertical scaling limits; "Thundering Herd" problems when thousands of users reconnect after network blips.
* **Key Concepts:** Database sharding with Vitess, Edge caching (Flannel).
* **Must-Read Topics:** 
  * Scaling datastores at Slack with **Vitess**.
  * Redesigning their real-time Gateway and Message Server architecture.

## 7. Pinterest Engineering
**URL:** https://medium.com/pinterest-engineering
**Core Focus:** Graph Databases, Asynchronous Job Processing, and Serving Machine Learning Models.
* **Real-World Problem:** Storing billions of Pin/Board/User relationships efficiently; unreliable asynchronous job execution.
* **Key Concepts:** Graph storage (Zen), Job queues (PinLater/Pacer).
* **Must-Read Topics:** 
  * Building **Zen**, their custom graph storage service.
  * Building scalable asynchronous job processing systems like **PinLater**.

## 8. Zerodha Tech Blog
**URL:** https://zerodha.tech/blog
**Core Focus:** Ephemeral distributed architectures, High-concurrency system designs in Go/Rust, Self-hosted SMTP scaling.
* **Real-World Problem:** Generating, digitally signing, and mailing 1.5+ million PDFs daily within strict regulatory windows.
* **Key Concepts:** Typst typesetting engine, S3 partition hashing key design, Nomad system jobs, Haraka SMTP cluster.
* **Must-Read Topics:**
  * [1.5+ million PDFs in 25 minutes](https://zerodha.tech/blog/1-5-million-pdfs-in-25-minutes/) ➡️ [Our Notes](file:///Users/rohit.kumar.4/Documents/interview-prep/engineering-blogs/zerodha-1-5-million-pdfs.md)

## 9. Maersk Engineering Internal Notes
**Core Focus:** Frontend component modularity, Vue 3, Clean architecture, Unit testing best practices.
* **Real-World Problem:** Presenting complex billing/metering tabular and tabbed data cleanly while maintaining unit-test coverage.
* **Key Concepts:** Presentational/Dumb components vs Container/Smart components, separation of utility files, mocking Pinia/components in Vue Test Utils.
* **Must-Read Topics:**
  * [isce-cp-ui PR #3304: Metering UI Component Architecture](https://github.com/Maersk-Global/isce-cp-ui/pull/3304) ➡️ [Our Notes](file:///Users/rohit.kumar.4/Documents/interview-prep/engineering-blogs/isce-cp-ui-pr-3304.md)

## How to Study These Blogs for SDE-2 Level

When reading these blogs, don't just look at the final solution. The real learning happens when you understand the journey. Use this framework:

1. **The Constraint:** What was the specific limit or problem they hit?
2. **The Options (Trade-offs):** What alternatives did they consider? 
3. **The "Why":** Why did they choose their specific path over the alternatives? What were the deciding factors?
4. **The Implementation:** How did they roll it out? (e.g., Shadow reading, dual writes).
5. **The Retrospective:** What did they learn 6-12 months *after* the implementation?

### Next Steps
If you want, I can fetch the content of specific famous articles and summarize them for you right here in this folder!