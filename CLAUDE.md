# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

This is a backend interview preparation repository. It contains two types of content:

1. **Concept notes** — Markdown files structured as: What / Why / How / Code Example / Interview Angles
2. **Runnable demos** — Spring Boot + Spring Cloud Maven projects that illustrate the concepts

The README lists planned topic folders (`spring-boot/`, `lld/`, `hld/`, `event-driven/`) that don't exist yet — only `microservices/` is currently populated.

## Build & Run (Maven — Java 17, Spring Boot 3.2.x, Spring Cloud 2023.x)

Each runnable demo is a Maven multi-module project under its own folder.

**Build all modules from the parent pom:**
```bash
cd microservices/service-discovery-eureka
mvn clean package -DskipTests
```

**Run a specific service:**
```bash
# Start Eureka Server first (port 8761)
cd eureka-server && mvn spring-boot:run

# Then start clients (inventory: 9081, order: 9082)
cd inventory-service && mvn spring-boot:run
cd order-service    && mvn spring-boot:run
```

**Run tests for a module:**
```bash
cd microservices/service-discovery-eureka/order-service
mvn test
```

## Project Structure

```
microservices/
  service-discovery-eureka/       # Multi-module Maven project
    pom.xml                       # Parent POM (manages spring-cloud-dependencies BOM)
    eureka-server/                # @EnableEurekaServer — registry at :8761
    inventory-service/            # Eureka client at :9081
    order-service/                # Eureka client at :9082, calls inventory via DiscoveryClient

  client-side-load-balancer/      # Standalone Java code snippets (no pom.xml — reference only)
    *.java                        # Illustrate RestTemplate, WebClient, Feign, custom LB
```

## Architecture Notes

### service-discovery-eureka demo

- **Order Service** uses `DiscoveryClient` to manually look up `inventory-service` instances from the local Eureka cache, then calls via plain `RestTemplate`. This is the raw, manual approach — intentionally not using `@LoadBalanced` so the discovery step is explicit.
- **Spring Cloud LoadBalancer** (the `client-side-load-balancer/` snippets) shows the abstracted approach: `@LoadBalanced RestTemplate` / `WebClient` / `FeignClient` intercept calls automatically.
- Eureka Server has `enable-self-preservation: true` — don't disable this in demos or you'll see false evictions.

### Content conventions

Each concept note follows the fixed five-section structure (What/Why/How/Code/Interview Angles). When adding new concept files, keep this structure. The `index.md` in each topic folder lists all files inside it — update it when adding new files.

## MCP Tools: code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the codebase.** The graph is faster, cheaper (fewer tokens), and gives
you structural context (callers, dependents, test coverage) that file
scanning cannot.

### When to use graph tools FIRST

- **Exploring code**: `semantic_search_nodes` or `query_graph` instead of Grep
- **Understanding impact**: `get_impact_radius` instead of manually tracing imports
- **Code review**: `detect_changes` + `get_review_context` instead of reading entire files
- **Finding relationships**: `query_graph` with callers_of/callees_of/imports_of/tests_for
- **Architecture questions**: `get_architecture_overview` + `list_communities`

Fall back to Grep/Glob/Read **only** when the graph doesn't cover what you need.

### Key Tools

| Tool | Use when |
|------|----------|
| `detect_changes` | Reviewing code changes — gives risk-scored analysis |
| `get_review_context` | Need source snippets for review — token-efficient |
| `get_impact_radius` | Understanding blast radius of a change |
| `get_affected_flows` | Finding which execution paths are impacted |
| `query_graph` | Tracing callers, callees, imports, tests, dependencies |
| `semantic_search_nodes` | Finding functions/classes by name or keyword |
| `get_architecture_overview` | Understanding high-level codebase structure |
| `refactor_tool` | Planning renames, finding dead code |

### Workflow

1. The graph auto-updates on file changes (via hooks).
2. Use `detect_changes` for code review.
3. Use `get_affected_flows` to understand impact.
4. Use `query_graph` pattern="tests_for" to check coverage.
