# Zerodha: Generating, Signing, and Mailing 1.5+ Million PDFs in 25 Minutes

This document is a beginner-friendly guide detailing Zerodha's architecture and engineering journey. It explains how they redesigned a slow 8-hour process into a fast 25-minute system to generate, sign, and email millions of PDF transaction reports (contract notes) daily.

---

## 💡 1. Quick Start: The "Grocery Store" Analogy

To understand why Zerodha rebuilt their system, let's use a simple analogy:

Imagine you run a massive grocery store. Every night, you must write a customized paper receipt (contract note) for every customer who shopped that day, sign it by hand, and mail it to them.

* **The Old Way (One Tired Worker):** You have one worker (a single, giant server) who does everything. They write the receipt in HTML, use a heavy web browser (Puppeteer) to take a screenshot to make it a PDF, sign it, and send it. By the time they finish 1.5 million receipts, **8 hours have passed**. If you get more customers, they simply run out of time before the store opens the next morning.
* **The New Way (A Fleet of Helpers):** You rent a fleet of 40 temporary workers (ephemeral Go instances on Nomad) for exactly **30 minutes**.
  * **Worker Group A** quickly writes the text templates.
  * **Worker Group B** compiles them into PDFs instantly using a lightweight writer (**Typst**).
  * **Worker Group C** uses a stamp machine to sign them (**OpenPDF** sidecar).
  * **Worker Group D** stuffs them into envelopes and mails them (**Haraka SMTP**).
  * Once all receipts are mailed, **you fire the helpers (terminate the servers) so you don't pay for idle time.**

---

## ⚙️ The Evolution: Comparing Old vs. New Stack

```mermaid
graph TD
    subgraph Old Stack (Slow & Monolithic)
        A1[Exchange CSV Dumps] -->|Slow Parsing| A2[Python + Jinja Templates]
        A2 -->|Heavy Memory/CPU| A3[Chrome via Puppeteer]
        A3 -->|HTML to PDF Conversion| A4[Java CLI Signer]
        A4 -->|Single Connection| A5[Postal SMTP Server]
    end

    subgraph New Stack (Fast & Distributed)
        B1[Exchange CSV Dumps] -->|Fast Concurrent Go| B2[Go Workers via Tasqueue]
        B2 -->|Read/Write Files| B3[Amazon S3 Storage]
        B3 -->|Lightweight Rust Binary| B4[Typst Compiler]
        B4 -->|Fast Concurrent Signing| B5[Java HTTP Sidecar]
        B5 -->|Pooled Connections| B6[Haraka SMTP Cluster]
    end
```

| Component | Old Stack | New Stack | Why this change helps a beginner understand? |
| :--- | :--- | :--- | :--- |
| **Orchestration** (System Coordinator) | Single vertically scaled server | Ephemeral Nomad Cluster | Instead of keeping a giant, expensive server running 24/7, they spawn instances only when needed and delete them immediately to save money. |
| **Job Queue** (Task Assigner) | Ad-hoc Python script | **Tasqueue** (Go library) | Handing jobs to workers in a clean line prevents workers from stepping on each other's toes. |
| **PDF Compiler** (The Writer) | Chrome via Puppeteer | **Typst** (written in Rust) | Spawning a whole web browser (Chrome) just to print a PDF is like driving a semi-truck to buy a carton of milk. Typst is a tiny, super-fast document compiler. |
| **PDF Signer** (The Stamp) | Java CLI tool | Java **OpenPDF** HTTP sidecar | Opening and closing a program 1.5 million times is slow. Keeping the program open in the background (as a "sidecar" service) and sending it requests is instant. |
| **Shared Storage** (The Filing Cabinet) | AWS EFS (Elastic File System) | **Amazon S3** (with custom partitioning) | EFS is like a shared network folder; it gets overwhelmed if 10,000 workers try to open small files at the same time. S3 is a highly optimized object store. |
| **Email Delivery** (The Mailman) | Postal SMTP | **Haraka SMTP** + `smtppool` | Haraka is written in Node.js and handles asynchronous network connections far faster than old SMTP software. |

---

## 🛠️ 2. Deep Dive: The Core Problems & How They Were Solved

### Problem 1: Converting Text to PDF is CPU-Heavy
* **What they tried first (LaTeX):** They tried `pdflatex` (a traditional tool used to write academic papers). It was 10x faster than Chrome, but it crashed on memory limits when generating 2000-page reports for heavy traders.
* **The Solution (Typst):** Typst is a modern alternative to LaTeX written in **Rust**.
  * **The Speed Difference:** A 2000-page report took **18 minutes** to build in LaTeX, but only **1 minute** in Typst!
  * **Deployment Benefit:** Typst is a single lightweight binary file. This makes container images tiny, allowing new servers to download and boot it up in seconds.

---

### Problem 2: The S3 "Grocery Checkout" Bottleneck
When workers write PDFs, they upload them to **Amazon S3** so the next worker can read them. S3 is incredibly fast, but it has limits:
* **The Limit:** You can only upload (PUT) 3,500 files per second under the same folder prefix.
* **The Mistake:** Zerodha originally named files using a timestamp ID (like `bucket/2CTgQ_user1.pdf`). Because these IDs all started with `2CTgQ`, S3 saw them as the *same folder prefix*. This is like having a grocery store with 10 checkout registers, but forcing every customer to stand in Register 1's queue. S3 returned `503 Slow Down` errors.
* **The Solution (Partition Hashing):** Zerodha changed the file paths to start with a number from 0 to 9 based on a hash (e.g., `bucket/0-tmp-pdf/`, `bucket/1-tmp-pdf/`). Now, the traffic is evenly split across 10 different S3 virtual queues, raising their limits tenfold.

```
❌ Bad Prefixing (All traffic hits one virtual S3 register):
bucket/2CTgQ_fileA
bucket/2CTgQ_fileB
bucket/2CTgQ_fileC

✅ Hashed Prefixing (Traffic split across 10 registers):
bucket/0-tmp-pdf/fileA
bucket/1-tmp-pdf/fileB
bucket/9-tmp-pdf/fileC
```

---

### Problem 3: Ephemeral Infrastructure (Paying Only for What You Use)
Instead of keeping a fleet of 40 servers running all day:
1. **12:00 AM (Start):** A scheduling system (**Rundeck**) triggers a script.
2. **12:05 AM (Spawn):** The script commands AWS to boot up 40 high-performance servers running **Nomad** (an easy-to-use alternative to Kubernetes).
3. **12:10 AM (Work):** The servers pull data, compile PDFs in Typst, sign them, and email them out.
4. **12:35 AM (Destroy):** A monitor script checks Redis, sees that all 1.5 million jobs are finished, and deletes all 40 servers.
5. **Cost:** Zerodha only pays for 25 minutes of server usage!

---

## 📚 3. Explaining the Tech Jargon (A Beginner's Dictionary)

If you are new to backend infrastructure, these terms can look intimidating. Here is what they actually mean:

### ⏱️ Ephemeral (Temporary Cloud)
* **Real-world Analogy:** Renting a moving truck for 2 hours to move apartments, rather than buying a truck and paying for parking all year.
* **In Tech:** **Ephemeral** means temporary or short-lived. In the cloud, instead of keeping virtual servers running forever (which costs money even when they do nothing), you programmatically create them, run your 25-minute job, and delete them immediately.

### 🖥️ Cluster (A Team of Servers)
* **Real-world Analogy:** A group of cashiers at a large supermarket. Instead of one cashier trying to scan a long queue of 1.5 million items, you open 40 registers to check out customers simultaneously.
* **In Tech:** A **cluster** is a group of individual physical or virtual servers (called **nodes**) working together over a network to act as a single powerful computer system.

### 🧭 HashiCorp Nomad (The Dispatcher)
* **Real-world Analogy:** The warehouse manager who monitors workers. When a task arrives, the manager says: *"Worker A, you have the least work right now, you take this. Worker B, you are out of memory, take a break."*
* **In Tech:** Nomad is a **workload orchestrator**. When you have a cluster of 40 servers, you don't manually log into each one to start your code. You write a job configuration file and give it to Nomad. Nomad automatically chooses which servers have free CPU capacity, runs your application containers there, and restarts them if they crash. (It is a simpler, faster alternative to the famous **Kubernetes**).

### 🏗️ Terraform (The Infrastructure Blueprint)
* **Real-world Analogy:** A drawing blueprint. Instead of telling construction workers where to place every single brick one-by-one, you give them a blueprint and a button that builds the house automatically.
* **In Tech:** Terraform is an **Infrastructure as Code (IaC)** tool. Instead of clicking buttons in the AWS Console to create servers, networks, and databases, you write a text file describing them. Terraform reads this file and automatically creates or deletes all those cloud systems securely in a single command.

### 📋 Rundeck (The Conductor / Orchestrator)
* **Real-world Analogy:** The factory line supervisor who blows the whistle to start the morning shift, checks the progress logs, and shuts down the machines at the end of the day.
* **In Tech:** Rundeck is a job scheduling tool with a web interface. It runs the scripts that coordinate the entire pipeline: first running Terraform to build the cluster, then running Nomad tasks, and finally running the teardown script.

### 🗃️ Redis (The Shared Whiteboard)
* **Real-world Analogy:** A central whiteboard in an office. As workers finish a customer's contract note, they check it off the whiteboard so everyone knows it's finished.
* **In Tech:** Redis is a super-fast, in-memory database. In this system, it acts as a **broker** (distributing jobs to workers) and a **state store** (keeping track of which of the 1.5 million PDFs have succeeded or failed so failed ones can be retried).

### ✉️ SMTP & Haraka (The Post Office & Mail Sorter)
* **Real-world Analogy:** **SMTP** is the language postal services use to agree on how to address envelopes. **Haraka** is a state-of-the-art automatic sorting machine that routes millions of letters per second without jamming.
* **In Tech:** SMTP (Simple Mail Transfer Protocol) is the standard method for sending email. Haraka is a highly scalable, node.js-based SMTP server cluster that sends outbound emails quickly without clogging connections.

### 🏍️ Sidecar Pattern (The Auxiliary Helper)
* **Real-world Analogy:** A sidecar attached to a motorcycle. The passenger in the sidecar does not drive the bike, but they can carry tools, maps, or handle communications to help the driver focus solely on driving.
* **In Tech:** A **sidecar** is a helper application that runs right next to your main application container on the same server. Here, the Go worker (the driver) handles the overall job flow, while a Java OpenPDF container (the sidecar passenger) stays booted up in memory next to it, handling complex PDF signing requests instantly without polluting the Go application's codebase.

### 📈 IP Reputation (Email Trustworthiness)
* **Real-world Analogy:** A credit score. If you pay your bills on time, banks trust you. If you consistently default, banks reject you.
* **In Tech:** Email servers (like Gmail or Yahoo) block incoming emails if the sender's IP address is known for sending spam. To self-host email servers, you must build and protect your **IP reputation** over years by only sending legitimate, transactional emails (like receipts) and ensuring users never mark them as spam.

---

## 🏆 Key Takeaways for SDE-2 Developers
1. **Avoid Over-Engineering Resource Management:** Don't waste time trying to predict how much memory/CPU each individual job needs. Just spin up a large pool of shared cores and let them burn through a unified queue.
2. **Keep Docker Images Small:** Keep your dependencies clean. Typst's single-binary layout meant fast container startups compared to LaTeX's massive library size.
3. **Understand S3 Partitioning:** High throughput systems must structure object storage keys randomly or evenly to distribute I/O requests across S3's physical partitions.
