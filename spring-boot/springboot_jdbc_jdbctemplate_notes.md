# Spring Boot: JDBC vs. JdbcTemplate (JPA Foundations) 🗄️

Before diving into JPA (Java Persistence API) and Hibernate, it is essential to understand how Java connects to databases at a lower level. 

In this guide, we will trace the evolution of data access in Java: from **Plain JDBC** (the manual, boilerplate way) to **Spring Boot JdbcTemplate** (the clean, automated way). Understanding this foundation is critical for SDE interviews!

```
                    DATA ACCESS EVOLUTION
┌──────────────────┐      ┌──────────────────┐      ┌──────────────────┐
│    Plain JDBC    │ ──►  │  Spring JDBC     │ ──►  │    Spring JPA    │
│  (Manual SQL,    │      │  (Automates connections,│      │ (ORM, Hibernate, │
│  boilerplate)    │      │  RowMappers)     │      │ no manual SQL)   │
└──────────────────┘      └──────────────────┘      └──────────────────┘
```

---

## 1. The Starting Point: What is JDBC? 🔌

**JDBC (Java Database Connectivity)** is a standard Java API that defines how a Java application connects to a database, runs queries, and processes results. 

JDBC is just a set of **interfaces** (like `Connection`, `Statement`, `PreparedStatement`, `ResultSet`). The actual implementation is provided by database vendors via **Database Drivers**:
* **MySQL:** Driver `Connector/J` (Class: `com.mysql.cj.jdbc.Driver`)
* **PostgreSQL:** `PostgreSQL JDBC Driver` (Class: `org.postgresql.Driver`)
* **H2 (In-Memory):** `H2 Database Engine` (Class: `org.h2.Driver`)

---

## 2. Plain JDBC Without Spring Boot (The Old Way) 👴🏽

In plain JDBC, you must write all connection, statement, and cleanup logic manually.

### Code Example: Plain JDBC implementation
```java
import java.sql.*;

public class UserDAO {

    // Helper method to establish a connection
    public Connection getConnection() throws ClassNotFoundException, SQLException {
        // 1. Manually load the database driver class
        Class.forName("org.h2.Driver");
        
        // 2. Establish connection using DriverManager
        return DriverManager.getConnection("jdbc:h2:mem:userDB", "sa", "");
    }

    public void createUserTable() {
        Connection conn = null;
        Statement stmt = null;
        try {
            conn = getConnection();
            stmt = conn.createStatement();
            String sql = "CREATE TABLE users(user_id INT AUTO_INCREMENT PRIMARY KEY, user_name VARCHAR(100), age INT)";
            stmt.executeUpdate(sql);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 3. Mandatory manual resource cleanup to prevent memory leaks!
            try { if (stmt != null) stmt.close(); } catch (SQLException se) {}
            try { if (conn != null) conn.close(); } catch (SQLException se) {}
        }
    }

    public void createUser(String userName, int userAge) {
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = getConnection();
            String sql = "INSERT INTO users(user_name, age) VALUES (?, ?)";
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, userName);
            pstmt.setInt(2, userAge);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { if (pstmt != null) pstmt.close(); } catch (SQLException se) {}
            try { if (conn != null) conn.close(); } catch (SQLException se) {}
        }
    }
}
```

### 🔴 The Boilerplate Code Nightmare:
If you look at the plain JDBC code, 80% of it is **boilerplate** (repetitive infrastructure code):
1. **Driver Class Loading:** Writing `Class.forName()` manually.
2. **Connection Management:** Opening connection sessions explicitly.
3. **Exception Handling:** Wrapping everything in complex nested `try-catch` blocks for checked `SQLException`.
4. **Resource Closing:** Closing `Statement`, `ResultSet`, and `Connection` in a `finally` block. Missing even one close call causes a connection pool leak!
5. **Manual Connection Pools:** Handling connection reuse limits manually.

---

## 3. Spring Boot `JdbcTemplate` (The Modern Way) 🚀

Spring Boot solves these boilerplate issues by providing the **`JdbcTemplate`** class. It takes care of opening/closing connections, executing statements, handling exceptions, and managing connection pooling under the hood.

### Step 1: Add Dependencies (`pom.xml`)
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

### Step 2: Configure Database (`application.properties`)
```properties
spring.datasource.url=jdbc:h2:mem:userDB
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true
```

### Step 3: Implement Database Access using `JdbcTemplate`

#### The Entity class (POJO):
```java
public class User {
    private int userId;
    private String userName;
    private int age;

    // Getters and Setters
}
```

#### The Repository class:
```java
package com.example.repository;

import com.example.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class UserRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate; // Spring Boot automatically configures and injects this!

    public void createTable() {
        jdbcTemplate.execute(
            "CREATE TABLE users (user_id INT AUTO_INCREMENT PRIMARY KEY, user_name VARCHAR(100), age INT)"
        );
    }

    public void insertUser(String name, int age) {
        String insertQuery = "INSERT INTO users (user_name, age) VALUES (?, ?)";
        // Update method is used for INSERT, UPDATE, and DELETE operations
        jdbcTemplate.update(insertQuery, name, age);
    }

    public List<User> getUsers() {
        String selectQuery = "SELECT * FROM users";
        
        // RowMapper maps each database row to a Java User object
        return jdbcTemplate.query(selectQuery, (rs, rowNum) -> {
            User user = new User();
            user.setUserId(rs.getInt("user_id"));
            user.setUserName(rs.getString("user_name"));
            user.setAge(rs.getInt("age"));
            return user;
        });
    }
}
```

---

## 4. How `JdbcTemplate` Works Under the Hood ⚙️

Here is how `JdbcTemplate` eliminates plain JDBC pains:

* **Driver class loading:** `JdbcTemplate` loads the driver automatically at application startup via Spring Boot auto-configuration.
* **DB Connection Making:** Connection sessions are fetched and managed automatically whenever you execute a query.
* **Closing Resources:** `JdbcTemplate` automatically closes statements, result sets, and returns database connections to the pool in a safe `finally` block behind the scenes.
* **Connection Pooling:** Spring Boot configures **HikariCP** by default (the fastest database connection pool). You can configure its size in properties:
  ```properties
  spring.datasource.hikari.maximum-pool-size=10
  spring.datasource.hikari.minimum-idle=5
  ```

### Granular Exception Translation 🛡️
* **In Plain JDBC:** You get a single, raw checked exception: `SQLException`. You have to parse database-specific error codes to know what failed.
* **In `JdbcTemplate`:** Spring intercepts `SQLException` and translates it into a granular, runtime (unchecked) subclass of Spring's **`DataAccessException`** hierarchy (e.g. `DuplicateKeyException`, `QueryTimeoutException`, `BadSqlGrammarException`).

---

## 4.1 Custom DataSource Configuration 🛠️

If you need to configure a custom DataSource bean programmatically instead of using `application.properties`:

```java
@Configuration
public class AppConfig {

    @Bean
    public DataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setJdbcUrl("jdbc:h2:mem:userDB");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
```

---

## 5. `JdbcTemplate` Method Reference (The Cheat Sheet) 📝

| Method Name | Use For | Sample Code |
| :--- | :--- | :--- |
| **`update(String sql, Object... args)`** | **INSERT**, **UPDATE**, **DELETE** (returns number of rows affected). | `jdbcTemplate.update("INSERT INTO users(name, age) VALUES(?, ?)", "Alice", 25);` |
| **`update(String sql, PreparedStatementSetter pss)`** | Performance-critical INSERT/UPDATE/DELETE queries. | `jdbcTemplate.update(sql, ps -> { ps.setString(1, "Alice"); ps.setInt(2, 25); });` |
| **`query(String sql, RowMapper<T>)`** | Retrieves **multiple rows** mapped to a List of objects. | `jdbcTemplate.query("SELECT * FROM users", (rs, rowNum) -> new User(rs.getString("name")));` |
| **`queryForList(String sql, Class<T> elementType)`** | Retrieves a **single column** across multiple rows. | `List<String> names = jdbcTemplate.queryForList("SELECT name FROM users", String.class);` |
| **`queryForObject(sql, Object[] args, Class<T>)`** | Retrieves a **single row** mapped to an object. | `User user = jdbcTemplate.queryForObject("SELECT * FROM users WHERE id = ?", new Object[]{1}, User.class);` |
| **`queryForObject(sql, Class<T>)`** | Retrieves a **single value** (e.g., aggregations). | `int count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);` |

---

## 6. SDE Interview Ready: Deep Dives 🧠

### Q1: How does Spring's `JdbcTemplate` handle connection leaks?
> **Answer:** Connection leaks occur when developers forget to close connection objects in a database session. `JdbcTemplate` prevents this entirely by wrapping query execution in template blocks that systematically close all statement/connection/result-set streams in a `finally` block, ensuring connections are safely returned to the connection pool under all conditions.

### Q2: What is the default connection pool used by Spring Boot, and why?
> **Answer:** Spring Boot uses **HikariCP** as its default connection pool. HikariCP is highly optimized, lightweight, and boasts superior performance compared to older connection pools (like Commons DBCP or C3P0) due to byte-code level optimizations, micro-second latency configurations, and array-storage optimizations.

### Q3: What is the difference between `RowMapper` and `ResultSetExtractor`?
> **Answer:** 
> * **`RowMapper<T>`:** Maps a single row of a `ResultSet` to a Java object at a time. It iterates through the dataset automatically, so you don't call `rs.next()`. Useful for mapping flat list structures.
> * **`ResultSetExtractor<T>`:** Passes the entire `ResultSet` to you at once. You must manage the iteration loop (`while (rs.next())`) yourself. This is ideal when you need to map complex nested structures (like a one-to-many relationship where you map multiple rows into a single parent object containing a list of child objects).
