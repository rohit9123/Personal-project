# High Level Design (HLD) - Async Excel Download with Push Notifications

> Source: https://maersk-tools.atlassian.net/wiki/spaces/THEMIS/pages/183849719044
> Last Modified: Jul 23, 2025 | Author: Mandeep Singh

---

To design a scalable, asynchronous download system where users can request heavy reports, and receive a download link once processing is completed. This prevents UI timeouts and improves user experience across multiple Maersk products (e.g., Shipment, Emission, DnD, Congestion).

* **Frontend:** Customer triggers the request to generate and download the Excel file.
* **Backend:** Processes the request asynchronously, stores the Excel file in Azure Blob Storage, and provides a download link.
* **Azure Blob Storage:** Stores the generated Excel file securely for customer retrieval.

---

### Approach 1: Kafka Based

**MVS UI → Reporting Service(BFF) → Kafka → Reporting Processor → Azure Blob**

Write a job to kafka topic and then we have consumer to consume the job and process it for further processing.

### Approach 2: Database-backed Job Queue (Polling Worker)

**MVS UI → Reporting Service(BFF) → Reporting Processor (direct call or async thread) → Azure Blob**

Write job to DB table and have a scheduled background job that polls unprocessed jobs and runs the report processor.

**Pros:**
* Persistent job storage
* No external queue system
* Can run multiple workers using DB-level locking or Redis locks

**Cons:**
* Adds latency (depending on polling interval)
* Not real-time like Kafka
* May require DB tuning to avoid polling bottlenecks

### Approach 3: Azure Function (Queue Triggered)

Instead of Kafka, use **Azure Queue Storage + Azure Functions**.

## Why Use Azure Function?

* **Auto-scaling**: Scales based on number of messages
* **Low ops**: No Kafka cluster or thread pool maintenance
* **Reliability**: Built-in retries, dead-lettering, timeouts
* **Cost-efficient**: Pay-per-execution model

```
[UI] → POST /api/download/{productId} (Spring Boot App)
           │
           ├─▶ Write message to Azure Queue Storage
           │     └── jobId, filters, productType
           │
           └── Respond to client with jobId

[Azure Function (Queue Triggered)]
           └── Triggered on message
                ├─▶ Generate Excel
                ├─▶ Upload to Azure Blob
                ├─▶ Update DB with status + download link
                └─▶ Push WebSocket/SSE Notification
```

**Pros:**
* Serverless scalability
* Resilient and cost-effective
* Built-in retry

**Cons:**
* Monitoring & debugging more involved

---

## Flow & API Calls

### 1. Trigger Excel Generation Request (Asynchronous)

**🔹 Action:** Customer initiates a request to generate a report (Excel file) for a given product.

**🔹 API Call:** `POST /downloads/initiateDownload`

**🔹 Request Body:**
* `productId`: Enum of supported products – `SHIPMENT`, `PORT_DISRUPTION`, `DND`, `EMISSION`
* Optional search filters

**Response:**
```json
{
  "jobId": "7b6f9932-fc9d-47c8-9235-2d7fd7e83b92",
  "status": "IN_PROGRESS"
}
```

**Purpose:** This `jobId` can be used to track or correlate download status asynchronously.

### 2. Fetch Download Reports

**🔹 Action:** UI dashboard displays **completed/failed reports** that the customer **has not yet acknowledged (i.e., not downloaded)**.

* User clicks on notification icon to get all reports
* User clicks on individual report to start the download.

**🔹 API Call:** `GET /downloads/getAll`

**🔹 Logic:**
* Fetches only:
  * `status = SUCCESS` OR `FAILURE`
  * `is_download_acknowledged = false`
* Sorted by most recent first

```json
[
  {
    "jobId": "abc123",
    "status": "SUCCESS",
    "message": "Report ready",
    "downloadUrl": "https://.../download.xlsx",
    "is_download_acknowledged": false
  }
]
```

**Purpose:** To show only those downloadable reports that have not yet been fetched or acknowledged by the user.

### 3. Acknowledge Download (Mark as Seen)

**🔹 Action**: Once the customer sees the notification, `/ack` API will be called to notify backend that report has been seen by user.

**API:** `PATCH /downloads/acknowledge`

**Request Body:**
```json
{
  "jobIds": [
    "7b6f9932-fc9d-47c8-9235-2d7fd7e83b92",
    "b8e92a22-8dcd-4efb-a57f-8de9a2099c19"
  ]
}
```

**Response:**
* `200 OK` – All jobs acknowledged successfully
* `400 Bad Request` – If no job IDs or invalid data provided

**🔹 Purpose:** To prevent showing the same notification again and again.

### 4. Real-time Notification (Push via SSE)

**Redis Pub/Sub:**

* **Redis:**
  * Acts as a simple Pub/Sub broker.
  * Broadcasts messages to all subscribers on the `download-notifications` channel.

#### Flow Recap: How It Works

1. **📤 Publisher Module (e.g., Reporting Processor)**
   * Updates job status in DB.
   * Publishes a Redis message with the `customerCode` on the `"download-notifications"` channel.
   * Code: `redisPublisher.publishNotification();`

2. **📥 Subscriber Module (e.g., Reporting Service/UI App)**
   * Listens to Redis topic `"download-notifications"`.
   * When a message is received, it calls: `downloadNotifier.pushNotification(customerCode);`
   * This pushes the latest count to the in-memory `sinkMap`, which powers the UI via SSE (`Flux<Integer>`).

**Pros:**
* Module decoupling via Redis
* Backpressure handled
* Efficient Flux streaming per customer
* Redis pub/sub is **extremely fast**, with sub-millisecond latency.
* There's **no persistence overhead** (unlike Kafka or DB-polling-based notification systems).

**Cons:**
* Extra Infra setup

### Solutions to Support Replaying Missed Notifications

#### Option 1: Use Redis Streams Instead of Pub/Sub

* Redis Streams allow you to store and consume messages with an offset.
* Each user (or session) can resume from where they left off.
* This way, when the user logs in, you fetch messages from the stream after their last seen message ID.

✅ Pros: Persistent and replayable.

#### Option 2: DB Polling for Notification

If real-time push isn't mandatory, your subscriber module can periodically poll the DB.

**Pros:**
* Zero infrastructure
* No Redis or Pub/Sub config
* Works for simple polling needs

**Cons:**
* Not real-time, but may suffice if "every few seconds" is acceptable.

### Pros and Cons – Redis Cache (with Redis Streams)

| **Usage** | **Pros** | **Cons** |
| --- | --- | --- |
| **Redis for Notification Queueing & Temporary State** | Very fast in-memory data store; Low latency stream reads (ideal for real-time use cases); Native support for stream offsets; Scalable and lightweight; Auto-expiry possible for cleanup | Data is volatile (unless persistence is configured); Redis is a single point of failure unless clustered; Memory overhead if stream data isn't expired; Scaling consumers may need stream coordination logic |

### Pros and Cons – Push Notification Approaches

| **Approach** | **Pros** | **Cons** |
| --- | --- | --- |
| **Client Polling** | Simple to implement; Works across all browsers; No persistent connection or server resource overhead | Not real-time (delay depends on polling interval); Inefficient due to frequent requests; Adds backend load |
| **WebSocket** | Real-time, full-duplex communication; Efficient over long-lived connections; Suitable for interactive apps | More complex implementation; Requires load balancer/WebSocket infra; Potential security considerations |
| **Server-Sent Events (recommended)** | Real-time one-way communication; Lightweight and easy to implement with Spring WebFlux; Auto-reconnect and message ID support | One-way only (server → client); Limited browser support (older browsers) |

---

## Backend Design Details

### 2. Queue-Based Job Management

* **Why**: A queue ensures jobs are processed in isolation, avoiding race conditions.
* **How:**
  * Use Azure Service Bus or Kafka for job queuing.
  * Assign each job a unique **Job ID** to track its lifecycle independently.
  * Return a job ID to the customer for status tracking.

### 3. Process the Job - Worker Isolation

* **Design:**
  * Each worker thread or process processes a single job at a time.
  * Workers fetch jobs from the queue and release them upon completion or failure.

* **Implementation:**
  * Use thread-safe background worker frameworks like **Spring Batch**, **ExecutorService**, or Azure Functions with **Singleton triggers**.
  * Ensure workers don't share state (e.g., avoid static variables).

* **Synchronization for Shared Resources:**
  * **Scenario**: When multiple jobs write files or update metadata in shared resources, enforce synchronization to avoid data corruption.
  * **Solution**: Use distributed locks (e.g., **Azure Redis**) when running multiple instances of the worker.

* If multiple requests come for different jobs (different `jobId`), **they will not block each other**.
* If multiple requests come for the same `jobId` (retries, duplicates), **only one will execute**, others will be blocked or rejected.

### 3. File Generation and Azure Blob Storage Upload

#### Thread-Safe File Writing

* **Use Temporary Files:**
  * Each job writes to a unique temporary file (e.g., `/tmp/job-{jobId}.xlsx`).
  * Use file-specific locks to prevent concurrent writes.

* **Libraries:**
  * Ensure libraries like Apache POI or NPOI are thread-safe by:
    * Instantiating separate workbook objects for each job.
    * Avoiding shared instances of resources like `SXSSFWorkbook`.

1. **Blob Naming:** Use unique blob names, e.g., `exports/{jobId}/file.xlsx`, to avoid overwriting files.
2. **Concurrency:** Use the Azure Blob Storage SDK's **thread-safe clients** for uploads. Avoid simultaneous uploads to the same blob.
3. **Generate Download Link:** Use **SAS token** to generate a secure, time-limited download link.

### 4. Status Tracking

#### 4.1 Job Metadata Storage

Use a **dedicated database table** to store metadata for each job:
* **Job ID**: Unique identifier.
* **Status**: Pending, In Progress, Completed, Failed.
* **Blob URL**: Link to the exported file.
* **Error Details**: Capture error messages or stack traces.

#### 4.2 Atomic Updates

* **Ensure thread-safety** in status updates:
  * Use database transactions to atomically update job status.
  * Avoid partial updates by ensuring "Completed" status is only set after file upload.

### Retry Mechanism

* **Transient Errors:**
  * Implement **exponential backoff** for retries on:
    * Network failures during blob uploads.
    * Temporary database or queue issues.

* **Max Retries:**
  * Configure a limit for retries to prevent infinite loops.
  * Mark jobs as "FAILED" after exhausting retries and notify the customer.

---

## Parallel Processing Handling for Kafka Consumer

### Key Design Elements

#### 1. Consumer Concurrency

Kafka consumer is configured to process messages **in parallel** using multiple threads:

```yaml
spring:
  kafka:
    listener:
      concurrency: 3  # Number of concurrent threads per consumer group
```

Or using multiple **consumer instances** in the same group to parallelize across partitions.

#### 2. Partition-Aware Processing

Kafka guarantees **message order within a partition**. By using a well-partitioned topic (e.g., by `jobId` hash), we ensure:

* Related messages are handled in sequence
* Unrelated messages can be processed in parallel

*Partitioning Strategy*: Use `jobId.hashCode()` to ensure related messages go to the same partition.

**ThroughPut Scaling:**

| Kafka topic partitions | ≥ 3 (to match consumer concurrency) |
| --- | --- |
| Spring Kafka concurrency | 3–5 based on CPU cores |

### Pros and Cons Table

| **Approach** | **Pros** | **Cons** |
| --- | --- | --- |
| **Single Kafka Consumer with Async Thread Pool** | Simple to scale within the same JVM; Only one consumer offset to manage; Good for controlling resource usage; Ensures ordering within a partition if required | Complex error handling (need to manage failures in threads); Increased memory usage if tasks pile up; Partition parallelism limited within a single consumer; Harder to track per-thread processing latency |
| **Multiple Kafka Consumers with Partition Concurrency** | Kafka handles partition assignment and parallelism; Easier horizontal scalability (scale by adding instances); Clean retry/rebalance semantics; Good separation of concerns per consumer instance | More resources consumed per instance (JVM overhead); More complex deployment; Possibility of rebalancing delays; Slightly harder to ensure ordering across partitions |

---

## Email Notification Design (via SendGrid)

### Purpose

Notify the customer via email when the report generation completes (either **SUCCESS** or **FAILURE**) with a link to download (if applicable).

### Integration Points

* Triggered inside the Kafka **consumer**, **after uploading the file** and **updating job status**
* Email template includes:
  * Product name (e.g., SHIPMENT, EMISSION)
  * Status (SUCCESS or FAILED)
  * Blob download URL (if successful)
  * File name
  * Expiry time (if applicable)

### Implementation Details

* **Library**: Use `SendGrid Java SDK`
* **Secure API Key**: Store in Vault
* **Template**: Use dynamic template ID from SendGrid (optional)

### Benefits

* Enhances user awareness
* Prevents silent report readiness in cases user misses SSE notification
* Easy to integrate with existing reactive consumer flow
