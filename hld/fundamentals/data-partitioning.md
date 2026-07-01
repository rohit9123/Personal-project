# HLD Core: Data Partitioning Patterns (Beyond Sharding for Noobs)

## Quick Summary (TL;DR)
* **Goal**: Scale databases beyond the limits of a single machine by dividing data across multiple physical database engines.
* **Database Federation**: Splitting a monolithic database into multiple independent databases based on **business domains / functions** (e.g., separating the Users table, Orders table, and Products table into different databases).
* **Functional Partitioning**: Splitting tables by **access pattern and lifecycle** (e.g., separating hot transactional shopping carts from cold archived historical invoice tables).
* **Vertical Partitioning**: Splitting a single table by **columns** (e.g., keeping lightweight user IDs and usernames in one table, and moving heavy user bio text and profile picture blobs to a separate table to optimize RAM caching).
* **Horizontal Sharding**: Splitting a single table by **rows** (e.g., users with IDs 1-1M go to Shard A, and IDs 1M-2M go to Shard B).

---

## 1. HLD Data Layout Patterns (The Big Picture)

When a system grows, a single database server eventually hits limits on CPU, RAM, disk space, and network bandwidth. To scale, we have to divide the data. 

Here is how the four major patterns divide a monolithic database:

```text
Monolithic Database
┌────────────────────────────────────────────────────────┐
│  USERS TABLE             ORDERS TABLE       LOGS TABLE │
│  id, username, bio, pic  id, amount, date   id, text   │
└────────────────────────────────────────────────────────┘

1. Database Federation (Split by Business Domain / Functional Boundaries)
┌────────────────────────┐  ┌──────────────┐  ┌──────────┐
│  DB 1: Users Service   │  │ DB 2: Orders │  │ DB 3:    │
│  USERS TABLE           │  │ ORDERS TABLE │  │ LOGS     │
└────────────────────────┘  └──────────────┘  └──────────┘

2. Vertical Partitioning (Split by Columns inside a Table)
┌────────────────────────┐  ┌────────────────────────────┐
│  Table A: Light Info   │  │ Table B: Heavy Blobs (Cold)│
│  id, username          │  │ user_id, bio, pic          │
└────────────────────────┘  └────────────────────────────┘

3. Horizontal Sharding (Split by Rows inside a Table)
┌────────────────────────┐  ┌────────────────────────────┐
│  Shard A (Users 1-1M)  │  │ Shard B (Users 1M-2M)      │
│  id, username, bio, pic│  │ id, username, bio, pic     │
└────────────────────────┘  └────────────────────────────┘
```

---

## 2. Database Federation (Functional Splitting)

**Database Federation** is the practice of dividing one large database into multiple smaller databases based on **business functions**. 

### The Department Store Analogy 🏬
Imagine a giant department store.
* **Without Federation**: The store keeps groceries, clothing, and high-end electronics in a single massive backroom warehouse managed by one manager. As the store gets busier, the warehouse gets cluttered, the manager gets overwhelmed, and retrieving items slows to a crawl.
* **With Federation**: The store splits the warehouse into three specialized buildings: a **Grocery Warehouse**, a **Clothing Warehouse**, and an **Electronics Warehouse**, each with its own local manager. 

In microservices, Federation is the foundation of the **Database-per-Service** pattern. The User Service gets its own database, and the Order Service gets its own database.

### Core Trade-offs:
* **Pros**:
  - **Scale Connections**: Write traffic is distributed across different database engines.
  - **Decoupled Schemas**: Teams can change the schema of the Orders database without affecting the Users database.
  - **Smaller Database Sizes**: Backups, restores, and index rebuilds are much faster.
* **Cons**:
  - **No Cross-Database Joins**: You cannot write `SELECT * FROM users JOIN orders ...`. You must perform **Application-Level Joins** (queries are run sequentially by the application code, which merges the results in memory).
  - **No Distributed ACID Transactions**: Transactions crossing database boundaries (e.g., deducting money from the Payment DB and creating an order in the Order DB) cannot use local database locks. You must implement patterns like **Saga** or **2-Phase Commit (2PC)**.

---

## 3. Functional Partitioning

**Functional Partitioning** splits data based on **how it is used and accessed** rather than purely by domain entities. 

### Examples of Functional Partitioning:
1. **Read vs. Write Isolation (CQRS)**:
   - Creating a read-only database replica optimized for fast query execution (using denormalized wide-column layouts) and a separate write-only database optimized for transactional consistency.
2. **Cold vs. Hot Data Split**:
   - **Hot Data**: Active shopping carts, current bids, and session states are stored in high-performance, expensive databases (like Redis or SSD-backed PostgreSQL).
   - **Cold Data**: Audit logs, 5-year-old payment invoices, and historical reports are moved to cheaper, slower storage systems (like AWS S3 or Snowflake Columnar databases).

---

## 4. Vertical Partitioning (Column-Based Splitting)

**Vertical Partitioning** is the practice of splitting a single database table **by columns**. 

### When to use it:
When you have a table where some columns are small and queried on almost every request (e.g., `id`, `username`, `status`), while other columns are extremely large and rarely accessed (e.g., `profile_picture_blob`, `user_resume_pdf`, `long_biography_text`).

### The Problem:
Database engines read data from disk in blocks/pages (typically 8KB). If a row contains a heavy 2MB profile picture blob, the database can only fit a few rows per page. When doing a simple lookup like `SELECT username FROM users`, the database is forced to load massive image bytes into RAM, wasting cache space.

### The Solution:
Split the table into two:

```sql
-- 1. Main Table (Lightweight: fits easily in RAM buffer pool)
CREATE TABLE users (
    id INT PRIMARY KEY,
    username VARCHAR(50),
    email VARCHAR(100)
);

-- 2. Detail Table (Heavy: loaded only when explicitly needed)
CREATE TABLE user_profiles (
    user_id INT PRIMARY KEY,
    bio TEXT,
    profile_picture_blob BYTEA,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

---

## 5. Architectural Comparison (The SDE-2 Cheat Sheet)

| Metric | Horizontal Sharding | Vertical Partitioning | Database Federation |
| :--- | :--- | :--- | :--- |
| **Splitting Axis** | **Rows** (Horizontal slices) | **Columns** (Vertical slices) | **Tables/Domains** (Functional slices) |
| **Complexity** | High (Requires sharding key routing, re-sharding) | Low (Simple table relationships) | High (Requires distributed transactions, app joins) |
| **Cross-Node Joins**| Hard (Requires scatter-gather queries across shards) | Yes (Simple SQL `JOIN` on primary key) | **No** (Must be done in application memory) |
| **Primary Benefit** | Scaled storage capacity for massive, homogeneous tables | Optimized memory buffer cache and query latency | Domain isolation and service-level autonomy |
| **Typical Use Case** | Scaling Twitter tweets, Uber driver location logs | Separating user credentials from profile picture blobs | Separating microservices (Payment DB vs. Catalog DB) |
