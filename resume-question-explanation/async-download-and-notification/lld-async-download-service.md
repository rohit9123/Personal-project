# Low-Level Design (LLD) Document: Async Download Service

> Source: https://maersk-tools.atlassian.net/wiki/spaces/THEMIS/pages/183849623087
> Last Modified: Jul 29, 2025 | Author: Mandeep Singh

---

### 1. Mono Repo Project Structure

```
download-service/
├── download-bff/                         # BFF Layer: UI API Gateway
│   ├── controller-api/                   # REST Controllers
│   │   └── DownloadController.java
│   ├── publisher-service/               # Kafka Publisher
│   │   └── DownloadPublisherService.java
│   └── status-service/                  # Download status tracking
│       ├── DownloadJobStatusService.java
│       ├── DownloadStatusRepository.java
│       └── DownloadStatusEntity.java
│
├── download-processor/                  # Core Processing & Azure Trigger
│   ├── dispatcher-service/              # Core Job Execution Logic
│   │   └── JobDispatcher.java
│   ├── azure-function-consumer/         # Azure Function Kafka Trigger
│   │   └── DownloadFunction.java
│   ├── blob-uploader/                   # Blob Upload Service
│   │   └── BlobUploader.java
│   ├── product-client/                  # ProductClient Interface & Factory
│   │   ├── ProductClient.java
│   │   ├── ProductClientFactory.java
│   │   └── impl/
│   │       └── ShipmentClient.java
│   ├── notification-service/            # Email Notifier (optional)
│   │   └── EmailSenderService.java
│
├── model/                               # Shared Models
│   ├── JobRequest.java
│   ├── DownloadStatus.java
│   └── SearchParams.java
│
├── application.yml
└── pom.xml
```

---

### 2. LLD Components Overview

#### 2.1 Controller Layer (download-service module)

```java
@PostMapping("/initiate-download")
public Mono<String> initiateDownload(@RequestBody JobRequest<SearchParams> jobRequest)
```
Generates UUID and calls `DownloadPublisherService`

```java
@GetMapping("/inbox")
public Flux<DownloadStatus> getAllJobs()
```
Returns all download jobs for a customer (for UI list rendering)

#### 2.2 Publisher Layer (download-service module)

* Creates jobId and pushes job to Kafka

#### 2.3 Status Service (download-service module)

* Stores job status in PostgreSQL (R2DBC)
* Provides `findAllByCustomerCode`

#### 2.4 Dispatcher (download-processor module)

```java
public Mono<ReportOutput> generateReport(final JobRequestDTO request) {
}
```

#### 2.5 BlobUploader (download-processor module)

* Uploads report to Azure Blob Storage
* Returns a signed SAS URL for download

```java
public class ReportOutput {
    private InputStream inputStream;
    private String fileName;
    private String contentType;
}

public Mono<String> upload(final ReportOutput reportOutput) {
}
```

#### 2.6 Update job status table with download URL and status as success or failure (download-processor module)

#### 2.7 Send Notification to redis cache (download-processor module)

#### 2.8 Read Notification from redis stream (notification-service module)

#### 2.9 Model Structure (model)

* `JobRequest<T>`: Holds request metadata
* `DownloadStatus`: Status of the download
* `SearchParams`: Flexible structure with optional filters

---

### 3. OpenAPI Endpoint Summary

```
POST   /downloads/initiate-download    Initiate a new async download job for a specific product
GET    /downloads/notifications        Subscribe to download-ready notifications for the customer
GET    /downloads/inbox                Get all reports visible in the user's download inbox
PATCH  /downloads/acknowledge          Acknowledge multiple download jobs by their job IDs
```

---

### 4. R2DBC Table Schema (PostgreSQL)

```sql
-- Table: download_job_status
CREATE TABLE IF NOT EXISTS download_job_status (
    job_id VARCHAR PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    message VARCHAR,
    download_url VARCHAR,
    customer_code VARCHAR(30) NOT NULL,
    created_at   timestamp with time zone,
    updated_at   timestamp with time zone,
    is_download_acknowledged BOOLEAN DEFAULT FALSE,
    file_name VARCHAR(255),
    product_name VARCHAR(100),
    expiry_time   timestamp with time zone,
    created_duration_ago VARCHAR
);

-- Indexes for optimal filtering and querying
CREATE INDEX IF NOT EXISTS idx_download_status__customer_code ON download_job_status (customer_code);
CREATE INDEX IF NOT EXISTS idx_download_status__status ON download_job_status (status);
```

---

### 5. Open API Spec

Reference: `https://github.com/Maersk-Global/API-JSON-Schema-Definitions/blob/a6e5ec1f590a6f12b7f8c7e25ce21299d14d0c90/apis/28-ISCE-CP/v1-Dereferenced/Download-Report-API-dereferenced.v1.yaml`

---

### Tech Stack

```
Java 21
Spring Boot (Reactive)
Apache Kafka (Job Queue)
Redis Streams (Notifications by SSE)
Azure Blob Storage
Docker
```
