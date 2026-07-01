# High Level Design (HLD) - Async Excel Upload with Email Notifications

> Source: https://maersk-tools.atlassian.net/wiki/spaces/THEMIS/pages/184297947282
> Last Modified: Nov 13, 2025 | Author: Mandeep Singh

---

## 1. Overview

The Async Excel Upload Service handles large Excel file uploads for shipment or configuration data. This service ensures non-blocking, scalable, and secure processing of uploaded files using an event-driven approach. It parallels the Async Download Service in architecture but focuses on inbound data ingestion.

## 2. Scope and Impact

The **Async Excel Upload Service** was introduced to overcome the limitations of the earlier **synchronous upload process**, where users experienced frequent timeouts and long waiting periods during file uploads — especially for large Excel files.

In the previous synchronous model:

* The UI maintained an open connection with the backend until the file was fully processed and validated.
* Large uploads often exceeded timeout limits (typically 60 seconds), resulting in user frustration and repeated upload attempts.
* Backend threads were blocked during upload and parsing, leading to poor scalability under high concurrency.
* **Impacted customers:**
  * Internal users (e.g. Kumho team) which needs to upload a long list of containers each time
  * External users - who upload more than 100 containers in each iteration

With the new **asynchronous, event-driven architecture**:

* Uploads are decoupled from processing, allowing immediate acknowledgment to the user.
* The file is safely stored in Azure Blob Storage first, and processing continues independently via Kafka-based async flows.
* UI no longer experiences timeouts or long waits — progress and completion are tracked via notifications (SSE or email).
* Backend resources are utilized efficiently, improving **system scalability, resilience, and user experience**.

This approach aligns with the broader architectural direction towards **non-blocking, reactive, and scalable backend services** within Maersk's digital ecosystem.

---

## 3. Design Options

Two main architectural approaches are evaluated for handling Excel uploads:

### Option 1: UI Uploads Directly to Blob Storage

* The UI uploads the Excel file directly to Azure Blob Storage using a pre-signed SAS token.
* The UI then triggers the Upload-Service API with the Blob file URL.
* Upload-Service publishes an UploadJobEvent to Kafka for asynchronous processing.
* Upload-Processor downloads the file from Blob, performs security scanning, parses, and validates the data.
* Suitable for very large files (>50MB) to avoid backend memory overhead.
* Recommended for low-latency uploads and where UI already supports SAS-based uploads.

### Option 2: Backend Receives Multipart File

* The UI sends the Excel file as a multipart/form-data request to the Upload-Service.
* Upload-Service uploads the file to Azure Blob Storage and sends an UploadJobEvent to Kafka.
* Upload-Processor retrieves the file from Blob, sends it to Threat Removal Service for security scanning, then processes it.
* Simplifies UI since it does not handle Blob directly.
* Recommended when upload security, validation, or file integrity checks must occur server-side before Blob upload.

### Pros and Cons - Option 1

**Pros:**
* Reduces backend load and memory consumption (UI handles file transfer directly).
* Faster uploads, especially for large files (>50MB).
* Suitable for distributed or micro-frontend environments already using Azure SAS tokens.

**Cons:**
* Requires UI to handle secure SAS token generation and expiry management.
* Increases complexity on UI side (retry, progress tracking, error handling).
* Threat Removal and validation cannot occur before Blob upload — potential risk if malicious files are uploaded.

### Pros and Cons - Option 2

**Pros:**
* Stronger security posture — file can be scanned and sanitized before Blob upload.
* Simplifies UI logic; no need to manage SAS tokens or Azure SDK.

**Cons:**
* Slightly higher backend resource utilization (temporary file buffering).
* Slower initial response for very large files compared to direct Blob upload.
* Needs robust async handling to avoid blocking I/O threads under load.
* Memory/CPU heavy (especially for 100+ MB Excel files).
* Risk of API timeouts or `OutOfMemoryError` under load.
* Not ideal for high concurrency (many parallel uploads).

---

## 4. Security Integration (Approach 2 - Threat Removal)

Before uploading any file to Azure Blob Storage, the file is first sent to the Threat Removal Service. This service sanitizes the file, removes potential malicious content, and returns a clean file for safe upload. This step ensures compliance with Maersk's MVS security standards and prevents infected files from entering the cloud environment.

---

## 5. Email Notification Design (via SendGrid)

#### Purpose

Notify the customer via email when the report upload process completes (either **SUCCESS** or **FAILURE**) with a link to download (if applicable).

#### Integration Points

* Triggered inside the Kafka **consumer**, **after uploading the file** and **updating job status**
* Email template includes:
  * Product name (e.g., SHIPMENT, EMISSION)
  * Status (SUCCESS or FAILED)
  * Blob download URL
  * File name
  * Expiry time (if applicable)

#### Implementation Details

* **Library**: Use `SendGrid Java SDK`
* **Secure API Key**: Store in Vault

### Benefits

* Enhances user awareness
* Prevents silent report readiness in cases user misses SSE notification
* Easy to integrate with existing reactive consumer flow

---

## 6. My Recommendation

After evaluating both architectural approaches, **Option 1 (UI uploads directly to Azure Blob using SAS URL)** is the **recommended approach** for the Async Excel Upload Service.

* **Eliminates timeout issues** seen in the previous synchronous upload model.
* **Optimized for large files** (>100 MB) and **high concurrency** scenarios.
* **Reduces backend load** by delegating file transfer to the client.
* **Improves user experience** with faster uploads, real-time progress, and immediate acknowledgment.
* **Easily integrates with Maersk's event-driven ecosystem**, using Kafka for downstream async processing.
* When combined with a **quarantine container + Threat Removal Service**, it ensures **security compliance** without reintroducing synchronous bottlenecks.

---

## 7. General Recommendation

| **Use Case** | **Recommended Option** | **Rationale** |
| --- | --- | --- |
| Small–medium files (<20 MB), low concurrency | **Option 2 (Backend handles multipart)** | Simpler integration, easy validation, minimal setup |
| Large files (>50 MB) or high concurrency, async system | **Option 1 (UI uploads directly to Blob)** | Best scalability, performance, and cost profile |
| Security scanning (Approach 2 MVS) | **Option 1 + Quarantine container + ThreatRemoval step** | Keeps backend clean, ensures file sanitization before use |

---

## 8. Minutes of Meeting (MoM)

**Discussion Date:** 12-11-2025
**Participants:** Engineering Team

**Key Decisions & Action Points:**

1. **Decision:** The team agreed to proceed with **Option 1 (UI uploads directly to Azure Blob using SAS URL)** as the preferred architectural approach for the Async Excel Upload Service.
2. **Rationale:** Option 1 effectively resolves the timeout and scalability issues faced in the previous synchronous upload model, while maintaining strong performance for large file uploads.
3. **Pending Clarification:** Business confirmation is required on:
   * The expected file size range for uploads (to validate SAS token expiry and chunking strategy) — **Pransh to confirm**
   * The type of data to be uploaded (shipment data, configuration templates, etc.) to finalize validation logic and storage tiering — **Pransh to confirm**
   * Need clarification on email notification. Is this a P0 task — **Pransh to confirm**
4. **Next Steps:**
   * Engineering team to design and implement SAS token generation API and Blob upload UI flow.
   * Business to share file specifications and sample datasets.
   * Mansi/Vivek to validate integration with the Threat Removal Service post-upload.
