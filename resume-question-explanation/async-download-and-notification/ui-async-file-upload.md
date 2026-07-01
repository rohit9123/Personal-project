# [UI] Async File Upload

> Source: https://maersk-tools.atlassian.net/wiki/spaces/THEMIS/pages/184306696921
> Last Modified: Nov 17, 2025 | Author: Ankita Ajay Bhasme

---

# 1️⃣ Background / Existing Flow

Currently, the upload process for various modules (**Shipments**, **Air Shipments**, **Emissions**, **Flexi Columns**) follows a **synchronous, frontend-driven** approach.

| **Module** | **Current Flow Summary** |
| --- | --- |
| **Shipments – Subscription Upload** | Frontend reads the entire Excel file. Iterates row by row. Calls API per subscription record. |
| **Shipments – Flexi Column Upload** | Frontend reads the entire Excel file. Splits into chunks (max 100 records each). Calls API per chunk. |
| **Air Shipment Upload** | Same as Subscription Upload (row-by-row API calls). |
| **Emission Upload** | Same as Flexi Column Upload (chunked API calls). |

### ⚠️ Existing Drawbacks

1. **Blocking Uploads:** Only one upload can occur at a time — the user must wait for full processing before initiating another upload.
2. **Limited Progress Visibility:** Progress is shown only within the upload widget. Once the user navigates away or closes the UI, progress information is lost.
3. **Frontend Overhead:** Reading and chunking data on the frontend adds latency, consumes memory, and introduces potential parsing errors.

---

## 2️⃣ Objective

Transition from **synchronous, UI-driven uploads** to **asynchronous, backend-managed uploads** with **real-time status updates** using **Server-Sent Events (SSE)**.

### 🎯 Goals

* Decouple data parsing from the UI.
* Enable concurrent uploads.
* Provide persistent user feedback via the notification drawer.
* Standardize upload and download pipelines under a unified async event framework.

---

## 3️⃣ Proposed Approach: Architecture Overview

* The UI uploads the Excel file directly to **Azure Blob Storage** using a **pre-signed SAS token**.
* The UI then triggers the **Upload-Service API** with the Blob file URL.
* **Upload-Service** publishes an `UploadJobEvent` to **Kafka** for asynchronous processing.
* **Upload-Processor** downloads the file from Blob Storage, performs **security scanning**, and **parses and validates** the data.
* Suitable for **very large files (>50MB)** to reduce backend memory overhead.
* Recommended for **low-latency uploads** and cases where the UI already supports SAS-based uploads.

---

## 4️⃣ Detailed Frontend Flow

### 4.1 File Upload

#### 🔹 Trigger

User selects and uploads a file via **UploadModal**.

#### 🔹 Behaviour

##### A. If *Async Upload* flag is **disabled**

Upload proceeds as per the **existing synchronous flow**, varying by feature (as described in Section 1).

##### B. If *Async Upload* flag is **enabled**

**Step a:** Upload triggers backend API to get a signed URL.

```
POST /upload/presigned-url
```

**Request Example**

```json
{
  "fileName": "shipment_upload.xlsx",
  "contentType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "productId": "SHIPMENT"
}
```

**Response Example**

```json
{
  "blobUrl": "https://isce-upload-container.blob.core.windows.net/uploads/shipment_upload.xlsx?sv=2025-11-12&sr=b&sig=abc123...",
  "expiryTime": "2025-11-12T13:30:00Z",
  "containerName": "isce-upload-container"
}
```

**Step b:** Once the signed URL is received, the browser creates a **background worker thread**.
**Step c:** The worker thread uploads the file from the browser to the corresponding Blob Storage.

#### Additional Behaviour:

* If the upload to Blob Storage **fails**, the browser implements an **auto-retry mechanism**.
* The retry logic is built into the worker thread.

### Retry Logic

* If the initial PUT request to Blob fails, the browser waits for **10 seconds**, then retries the same upload request.
* If a failure response is received again, the retry process continues with the same delay.
* If **no response** (timeout) is received within the expected window, the worker thread **retries again** until the retry max threshold is reached (configurable per implementation).

### POC Analysis

* In a POC with a **35 MB** file, an upload failure scenario resulted in receiving a failure response in approximately **5 seconds**.
* Based on this observation, adding a **10-second wait window** before retrying ensures:
  * Network stabilization time
  * Avoiding aggressive retry storms
  * Higher probability of recovery after temporary network interruptions

**Step d:** After the upload (success or failure), it triggers the backend API:

```
POST /upload/initiate
```

**Purpose:** Initiates a new asynchronous file upload request and creates a job entry for tracking.

**Request Example**

```json
{
  "fileName": "shipment_data.xlsx",
  "fileSize": 5242880,
  "productId": "shipment",
  "uploadType": "VISIBILITY_UPLOAD"
}
```

**Response Example**

```json
{
  "jobId": "b7e9e25a-cc8f-4a63-b9d3-bbcf7e45d01a",
  "uploadUrl": "https://azure.blob.core.windows.net/uploads/b7e9e25a-cc8f-4a63-b9d3-bbcf7e45d01a?sp=rw",
  "expiryTime": "2025-11-12T14:30:00Z",
  "status": "INITIATED"
}
```

---

### 4.2 Bell Notifications

#### 🔹 Trigger

Server-Sent Events (SSE) listener on the UI.
`App.vue` establishes a global SSE connection via `useSSE()` on `onMounted()`.

#### 🔹 Behaviour

```
GET /notifications
```

| **Event Type** | **Payload Example** | **Frontend Behaviour** |
| --- | --- | --- |
| `UPLOAD_STARTED` | `{ "jobId": "123", "fileName": "shipment.xlsx", "type": "UPLOAD_STARTED" }` | Status → *In Progress* |
| `UPLOAD_COMPLETED` | `{ "jobId": "123", "resultFileUrl": "..." }` | Status → *Notification count updated* |
| `UPLOAD_FAILED` | `{ "jobId": "123", "errorMessage": "Validation failed", "errorFileUrl": "..." }` | Status → *Notification count updated* |

---

### 4.3 Notifications Drawer

#### 🔹 Trigger

Clicking the **bell icon** opens the notification drawer.

#### 🔹 Behaviour

##### B. If *Async Upload* flag is **enabled**

```
GET /upload/inbox
```

**Response Example**

```json
[
  {
    "jobId": "b7e9e25a-cc8f-4a63-b9d3-bbcf7e45d01a",
    "fileName": "shipment_data.xlsx",
    "status": "SUCCESSFUL",
    "productId": "shipment",
    "message": "Upload and processing completed successfully",
    "createdAt": "2025-11-12T14:00:00Z",
    "updatedAt": "2025-11-12T14:45:00Z"
  },
  {
    "jobId": "6d4c5e82-79cd-4e02-b9a1-930cb2f192d2",
    "fileName": "invalid_data.xlsx",
    "status": "FAILED",
    "message": "Validation error: Missing column 'flightNumber'",
    "createdAt": "2025-11-12T12:00:00Z",
    "updatedAt": "2025-11-12T12:30:00Z"
  }
]
```

#### Drawer Tabs

* **Default (All):** Displays all notifications (upload + download) in descending order of time.
* **Upload:** Filters notifications specific to upload operations.

### If Status = FAILED

* The notification row will show the status as **Failed**.
* A **"Download Error Report"** action will be shown.
* When the user clicks **Delete**, the notification will be removed from the list.
  * This triggers an API call: `DELETE /upload/delete?jobId=<JOB_ID>`
  * This removes the job entry from the backend tracking table as well.

### If Status = SUCCESS

* The notification row will show **Success**.
* A **"Download Success Report"** action will be available.
* Clicking **Download** retrieves the processed file from Blob Storage.

---

### 4.4 Acknowledgement

#### 🔹 Trigger

Opening the notifications drawer.

#### 🔹 Behaviour

```
POST /upload/acknowledge
```

**Purpose:** Resets the new notification count to **0**.

---

## ✅ Benefits of This Approach

1. **Reduced Upload Latency:** Uploading directly to Blob Storage avoids backend relay, reducing overall latency.
2. **Non-Blocking User Experience:** Users can upload multiple files concurrently without waiting for one to finish.
3. **Bypassing APG Limitations:** Uploads are no longer constrained by API Gateway data size limits.
4. **Persistent Notifications:** Users receive upload completion or failure notifications even after returning to the UI later.

---

## ⚙️ Edge Cases / Current Limitations

* **File Size Restriction:** Current agreement restricts users to files of **20 MB** maximum size.
* **Browser Closure During Upload:** Uploads are **not supported** if the browser is closed immediately after initiation.
