# Spring Boot N+1 Query Problem & Pagination Observability Demo

This project demonstrates two core database performance challenges and architectural optimizations in Spring Boot applications:
1. **Episode 1 — The N+1 Query Problem**: Solving ORM lazy-loading cascades using **JOIN FETCH** and **@EntityGraph**.
2. **Episode 2 — Pagination at Scale**: Comparing **SQL Offset**, **Keyset (Cursor-based)**, and **Deferred Join** pagination strategies under deep-page scanning and connection pool stress.

The application includes an end-to-end **Observability Stack** (Metrics, Distributed Tracing, and Dashboards) built with **Prometheus**, **OpenTelemetry Collector**, **Grafana Tempo**, and **Grafana**.

---

## 🏛️ Architecture & Telemetry Pipeline

```
                                      ┌────────────────────────────────────────────────────────┐
                                      │                   SPRING BOOT APP                      │
                                      │                                                        │
                                      │  ┌────────────────────────┐ ┌──────────────────────┐  │
                                      │  │ Spring Boot Actuator   │ │ Micrometer Tracing   │  │
                                      │  │ (micrometer-prometheus)│ │ (bridge-otel + AOP)  │  │
                                      │  └────────────┬───────────┘ └──────────┬───────────┘  │
                                      └───────────────┼────────────────────────┼──────────────┘
                                                      │                        │
                        [ PULL / Scrape Every 2s ]    │                        │ [ PUSH OTLP HTTP ]
                         GET /actuator/prometheus     │                        │ localhost:4318/v1/traces
                                                      ▼                        ▼
                                          ┌──────────────────────┐  ┌───────────────────────┐
                                          │      Prometheus      │  │    OTel Collector     │◄──────┐
                                          │     (Port 9090)      │  │      (Port 4318)      │       │ [ Scrapes DB Stats ]
                                          └───────────┬──────────┘  └──────────┬────────────┘       │ pg_stat_database
                                                      │                        │                    │
                                                      │ [ Scrapes OTel ]       │ [ PUSH OTLP gRPC ] │
                                                      │ otel-collector:8889    │ tempo:4317         │
                                                      │                        ▼                    │
                                                      │             ┌───────────────────────┐       │
                                                      │             │     Grafana Tempo     │       │
                                                      │             │   (Traces Storage)    │       │
                                                      │             └──────────┬────────────┘       │
                                                      │                        │                    │
                                                      ▼                        ▼                    │
                                        ┌────────────────────────────────────────────────────────┐  │
                                        │                   GRAFANA (Port 3000)                  │  │
                                        │                                                        │  │
                                        │  ┌──────────────────────┐  ┌────────────────────────┐  │  │
                                        │  │ Data Source:         │  │ Data Source:           │  │  │
                                        │  │ Prometheus (Metrics) │  │ Tempo (Traces)         │  │  │
                                        │  └──────────────────────┘  └────────────────────────┘  │  │
                                        └────────────────────────────────────────────────────────┘  │
                                                                                                    │
                                                    ┌────────────────────────┐                      │
                                                    │   PostgreSQL 16 DB     ├──────────────────────┘
                                                    │   (Port 5432)          │
                                                    └────────────────────────┘
```

---

## 🚀 Quick Start

### 1. Prerequisites
* **Docker Desktop** installed and running.
* **JDK 21+** (or JDK 25).
* **Maven** (or use the included `./mvnw` wrapper).

---

### 2. Start the Docker Infrastructure

Run Docker Compose from the project root directory:

```bash
docker compose up -d
```

Verify all 5 services are running:

```bash
docker compose ps
```

| Container | Service | Port | Description |
| :--- | :--- | :--- | :--- |
| `btc-postgres` | PostgreSQL 16 | `5432` | Database (`ecommerce`, user: `postgres`, pass: `password`) |
| `btc-prometheus` | Prometheus | `9090` | Time-series metrics engine (scrapes Spring Boot + OTel Collector) |
| `btc-tempo` | Grafana Tempo | `3200` | Distributed tracing backend |
| `btc-otel-collector` | OpenTelemetry Collector | `4317` (gRPC), `4318` (HTTP), `8889` (Prometheus exporter) | Receives traces & collects PostgreSQL engine metrics |
| `btc-grafana` | Grafana UI | `3000` | Pre-provisioned dashboards with Prometheus & Tempo datasources |

---

### 3. Run the Spring Boot Application

Start the app using Maven:

```bash
# Windows PowerShell / CMD
./mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```

The application will start on port `8080`.  
On initial boot, [DataSeederConfig.java](src/main/java/com/btc/nplus1/seed/DataSeederConfig.java) and [DataSeederOrderItem.java](src/main/java/com/btc/nplus1/seed/DataSeederOrderItem.java) automatically populate the database with users and thousands of orders.

---

## 🧪 Episode 1: Testing N+1 Query Problem & Solutions

### 1. Trigger the N+1 Query Problem (Slow)
Fetches 50 orders using standard lazy loading. Accessing `order.getItems()` triggers **50 additional child SQL queries**:

```bash
curl "http://localhost:8080/api/orders?strategy=nplus1&limit=50"
```

### 2. Solution 1: JPQL JOIN FETCH (Fast)
Fetches orders and items in **a single SQL query** using `LEFT JOIN FETCH`:

```bash
curl "http://localhost:8080/api/orders?strategy=joinfetch&limit=50"
```

### 3. Solution 2: Declarative @EntityGraph (Fast)
Fetches orders and items in **a single SQL query** using Spring Data JPA `@EntityGraph`:

```bash
curl "http://localhost:8080/api/orders?strategy=entitygraph&limit=50"
```

### 4. Lightweight Summary Query
Reads only parent order fields without loading child items:

```bash
curl "http://localhost:8080/api/orders/summary"
```

---

## ⚡ Episode 2: Offset vs Keyset vs Deferred Join Pagination

When datasets grow to millions of rows, pagination performance diverges drastically depending on the strategy:

| Strategy | Endpoint | Time Complexity | Index Scan vs Heap Scan | Connection Pool Impact |
| :--- | :--- | :--- | :--- | :--- |
| **Standard Offset** | `/api/orders/offset` | $O(N)$ scanning | Scans & discards $N$ rows from heap + runs expensive `COUNT(*)` | High pool starvation on deep pages |
| **Keyset (Cursor)** | `/api/orders/keyset` | $O(1)$ constant | Direct B-Tree seek `(created_at, id) < (cursor_date, cursor_id)` | Zero starvation, flat latency |
| **Deferred Join** | `/api/orders/deferred` | $O(N)$ index-only | Skips heap lookup in subquery using covering index, joins only target 20 rows | Moderate latency, low buffer churn |

### 1. Offset Pagination
Standard SQL `LIMIT ? OFFSET ?` paired with Spring Data `Pageable`:

```bash
# First page
curl "http://localhost:8080/api/orders/offset?page=0&size=20"

# Deep page (High latency, high buffer churn)
curl "http://localhost:8080/api/orders/offset?page=500&size=20"
```

* **Why it degrades**: PostgreSQL must read all previous rows from disk/buffer into memory and discard them. The accompanying `count(*)` query scans the entire relation.

### 2. Keyset (Cursor-based) Pagination
Seek-based pagination using a composite cursor `(created_at, id)` encoded in Base64:

```bash
# 1. Fetch first page
curl "http://localhost:8080/api/orders/keyset?size=20"

# Response returns:
# {
#   "content": [...],
#   "nextCursor": "MTcwOTY0MTIxMDAwMDoxMDI0",
#   "hasMore": true
# }

# 2. Fetch next page using the cursor
curl "http://localhost:8080/api/orders/keyset?cursor=MTcwOTY0MTIxMDAwMDoxMDI0&size=20"
```

* **Why it is fast**: Executes `WHERE (o.created_at, o.id) < (:createdAt, :id) ORDER BY o.created_at DESC, o.id DESC LIMIT 21`. PostgreSQL performs an index seek directly to the target row without scanning or discarding previous records.

### 3. Deferred Join Pagination
Optimizes offset queries when random access to a specific page number is strictly required:

```bash
curl "http://localhost:8080/api/orders/deferred?page=500&size=20"
```

* **Why it is fast**: An inner subquery `SELECT id FROM customer_orders ORDER BY created_at DESC LIMIT 20 OFFSET 10000` scans only the narrow index. The outer query joins only the 20 matched IDs back to the main table.

---

## 📊 Grafana Dashboards

Grafana is pre-configured to automatically load datasources and the Episode 2 pagination dashboard:

* **URL**: `http://localhost:3000`
* **Credentials**: `admin` / `admin`
* **Dashboard Path**: **Dashboards** $\rightarrow$ **Break The Code** $\rightarrow$ **Break The Code — Episode 2: Offset vs Keyset Pagination**

### Included Dashboard Panels:

1. **PostgreSQL: Read Amplification (Blocks Read per Live Row Fetched)**
   * Compares total block hits and disk reads against rows fetched.
   * Keyset pagination remains flat (~2–4 blocks/row), while deep offset climbs into thousands of blocks.
2. **PostgreSQL: Buffer Cache Hit Ratio**
   * Measures buffer pool hit efficiency (`sum(blks_hit) / (sum(blks_hit) + sum(blks_read)) * 100`).
   * Heavy offset queries evict warm data from shared buffers, creating visible dips.
3. **Application: HTTP p95 Tail Latency by Route**
   * Real-time $p95$ tail latency comparison for `/api/orders/offset`, `/api/orders/keyset`, and `/api/orders/deferred`.
4. **HikariCP: Pool Starvation (Active vs Pending Threads)**
   * Visualizes pool exhaustion (`HikariPool-Orders`).
   * Deep offset queries hold connections longer, causing pending thread spikes and connection timeouts.
5. **Application: Throughput by Route (req/sec)**
   * Tracks request throughput per endpoint under load.
6. **Service Layer: @Observed Method Latency (p95)**
   * Internal execution time measured by Micrometer `@Observed` on `OrderService#getOrdersOffset`, `getOrdersKeyset`, and `getOrdersDeferred`.

---

## 📜 Hibernate Logs & Session Metrics

### 1. Statement Count Summary
Thanks to `hibernate.generate_statistics: true` in `application.yaml`, Hibernate logs session execution metrics:

```text
Session Metrics {
    2841600 nanoseconds spent executing 51 JDBC statements;
    124000 nanoseconds spent preparing 51 JDBC statements;
    0 nanoseconds spent executing 0 JDBC batches;
    ...
}
```

### 2. Distributed Tracing Correlation IDs
Logs include the `[applicationName,traceId,spanId]` tag for correlation:

```text
INFO [nplus1,4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7] c.b.nplus1.controller.OrderController : Received getOrdersOffset request: page=0, size=20
```

---

## 🔍 How to Observe Traces in Grafana Tempo

1. In Grafana (`http://localhost:3000`), click **Explore** (compass icon).
2. Select **Tempo** from the datasource dropdown.
3. Switch query mode to **TraceQL** and run:
   ```traceql
   { resource.service.name = "nplus1" }
   ```
4. Click on any Trace to view the waterfall spans:
   * Controller span: `http get /api/orders/keyset`
   * Service span: `OrderService#getOrdersKeyset`
   * SQL JDBC query spans.

---

## 🛠️ Useful Docker Commands

```bash
# Restart OpenTelemetry Collector with updated metrics config
docker compose restart otel-collector

# Restart Grafana to reload provisioned dashboards
docker compose restart grafana

# View live OpenTelemetry Collector logs
docker logs -f btc-otel-collector

# Stop all containers
docker compose down

# Stop all containers and wipe volume data (resets database)
docker compose down -v
```
