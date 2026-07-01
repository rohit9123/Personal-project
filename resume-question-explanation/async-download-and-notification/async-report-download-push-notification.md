# Asynchronous Report Download with Push Notification

> Source: https://maersk-tools.atlassian.net/wiki/spaces/THEMIS/pages/183868166856
> Last Modified: Jul 21, 2025 | Author: Shikher Mishra

---

## 1. Problem Statement

The current "Download Report" functionality suffers from critical limitations:

* When users request large reports, the synchronous API call often times out, resulting in failed downloads.
* This undermines the user experience and leads to support overhead due to failed download attempts.

## 2. Proposed Solution

To address the issue, we propose shifting from a synchronous report generation model to an asynchronous, event-driven architecture using Server-Sent Events (SSE).

**Implementation Overview:**

* **Asynchronous Processing:**
  * When a user requests a report, the backend queues a background job for report generation.
  * The API responds immediately with a confirmation of job initiation.
  * We need to place a ON/OFF flag, which will allow us to toggle from async to sync and vice-versa.

* **Push Notification with SSE:**
  * A persistent SSE connection is established between the frontend and backend upon application load.
  * Once the report is ready, the backend pushes a notification message over the SSE stream.
  * The frontend displays a notification icon/badge to alert the user.

* **Frontend Download Trigger:**
  * The user clicks the notification to open notification drawer and initiate the actual download of the ready report.
  * An `/downloads/acknowledge` endpoint is called to mark all notifications as viewed.

## 3. Approach for Push Notification

* Communication: Server-Sent Events (SSE)

**Flow Summary:**

1. User **/initiateDownload** report download → API triggers background job.
2. Job processes and stores report → Upon completion, backend sends SSE push **/notification**.
3. Ready to download reports will be automatically downloaded.
4. Frontend receives notification → User clicks to download **/acknowledge** API is called.
5. User clicks on notification → open notification drawer → call **/getAll** Notification.

## 4. SSE Notification Flow

[Diagram: SSE flow between frontend and backend]

## 5. Polling vs WebSocket vs SSE vs REST API

| **Mechanism** | **Direction** | **Initiated By** | **Server-Initiated Messages** | **Type** |
| --- | --- | --- | --- | --- |
| Polling | Client → Server | Client | ❌ No | Pull |
| WebSocket | Both ways | Client | ✅ Yes | Push (bi-directional) |
| **SSE** | Server → Client | Client | ✅ Yes | **Push (one-way)** |
| REST API | Client → Server | Client | ❌ No | Pull |

## 6. Why SSE Was the Right Fit

For this particular feature, only one-way communication is required - from the backend to the frontend - to notify users when a report is ready.

* WebSockets provide full-duplex communication but introduce unnecessary complexity and overhead for our use case.
* Server-Sent Events (SSE) are simpler to implement and maintain, offering a lightweight and efficient solution for unidirectional, real-time updates.
* SSE also natively supports automatic reconnection and event IDs, which aligns well with our approach to resend missed notifications.

## 7. Edge Case Consideration

**Scenario: Tab/Browser Closed Before Notification Received**

* **Problem**: SSE connection is lost, and the notification is never seen by the user.

* **Solution 1:**
  * Backend persists unacknowledged notifications in storage (Redis Cache).
  * Upon reconnection of SSE, the backend re-sends any unacknowledged notifications.
  * User still gets notified after returning to the app.

* **Solution 2:** Use Service worker + push API

**Comparison: Service Workers with Push API vs Server-Sent Events (SSE)**

| **Feature** | **Server-Sent Events (SSE) ✅** | **Service Worker + Push API ✅** |
| --- | --- | --- |
| Requires page to be open | ✅ Yes | ❌ No — works even when tab closed |
| Real-time / streaming updates | ✅ Yes — continuous stream | ❌ No — push messages are occasional |
| Works offline | ❌ No | ✅ Yes (cached + background) |
| Needs user permission | ❌ No | ✅ Yes — user must allow notifications |
| Easy to implement | ✅ Very simple | ❌ More complex (VAPID keys, push server) |
| Can trigger browser notification | ⚠️ Only when tab is open | ✅ Yes — native push notification |
| Supported on all browsers | ⚠️ Not on IE, partial in Safari | ✅ Supported on all modern browsers |

## 8. API Summary

```
POST   /downloads/initiateDownload    Initiates report generation (returns job initiation status)
GET    /downloads/notifications       SSE endpoint to receive live notifications
POST   /downloads/acknowledge         Marks notifications as viewed by the user
GET    /downloads/getAll              To show only those downloadable reports that have not yet been fetched or acknowledged by the user.
```

## 9. Conclusion

This solution ensures a robust, scalable, and user-friendly approach to handling large report downloads:

* Eliminates timeout failures through background processing.
* Enhances user experience with real-time, event-driven notifications.
* Maintains reliability across edge cases using persistent and resumable notification logic.

This change aligns with modern application design principles and lays a solid foundation for future extensibility.
