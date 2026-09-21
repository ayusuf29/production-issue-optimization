# Spring Boot Performance & Observability Masterclass

This project demonstrates core backend performance challenges, architectural mitigations, and deep production observability in Spring Boot applications across three episodes:
1. **Episode 1 — The N+1 Query Problem**: Solving ORM lazy-loading cascades using **JOIN FETCH** and **@EntityGraph**.
2. **Episode 2 — Pagination at Scale**: Comparing **SQL Offset**, **Keyset (Cursor-based)**, and **Deferred Join** pagination strategies under deep-page scanning and connection pool stress.
3. **Episode 3 — Caching: Stampede, Penetration & Invalidation Cascades**: Preventing database pool exhaustion using **Distributed Mutex (Redisson RLock)**, **Double-Checked Locking**, **Empty Sentinels**, and **Randomized TTL Jitter**.

The application includes an end-to-end **Observability Stack** (Metrics, Distributed Tracing, and Dashboards) built with **PostgreSQL 16**, **Redis 7**, **Prometheus**, **OpenTelemetry Collector**, **Grafana Tempo**, and **Grafana**.

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
                                              ▲                                                     │
                     [ Scrapes Redis Stats ]  │         ┌────────────────────────┐                  │
                     redis-exporter:9121 ─────┘         │    PostgreSQL 16 DB    ├──────────────────┘
                                                        │    (Port 5432)         │
                                                        └────────────────────────┘
                                                                    ▲
                                                        ┌───────────┴────────────┐
                                                        │      Redis 7 (L2)      │
                                                        │      (Port 6379)       │
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

Verify all services are running:

```bash
docker compose ps
```

| Container | Service | Port | Description |
| :--- | :--- | :--- | :--- |
| `btc-postgres` | PostgreSQL 16 | `5432` | Database (`ecommerce`, user: `postgres`, pass: `password`) |
| `btc-redis` | Redis 7 | `6379` | In-memory key-value store and distributed lock provider |
| `btc-redis-exporter` | Redis Exporter | `9121` | Exports Redis memory, ops, and CPU metrics to Prometheus |
| `btc-prometheus` | Prometheus | `9090` | Time-series metrics engine (scrapes Spring Boot, OTel & Redis) |
| `btc-tempo` | Grafana Tempo | `3200` | Distributed tracing backend |
| `btc-otel-collector` | OpenTelemetry Collector | `4317` (gRPC), `4318` (HTTP), `8889` (Prometheus) | Receives traces & collects PostgreSQL engine metrics |
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
On initial boot:
- [DataSeederConfig.java](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/seed/DataSeederConfig.java) seeds users and customer orders.
- [CatalogDataSeeder.java](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/seed/CatalogDataSeeder.java) seeds catalog products, categories, and specifications (`hot-deal`, `prod-1001`).

---

## 🛡️ Episode 3: Caching — Stampede, Penetration & Invalidation Cascades

### The 3 Enterprise Caching Vulnerabilities

| Vulnerability | Root Cause | Failure Mode | Mitigation Solution |
| :--- | :--- | :--- | :--- |
| **Cache Stampede (Dogpiling)** | A hot key expires or is purged under high traffic; multiple threads get `null` simultaneously. | Concurrently fire 50+ identical expensive SQL queries; HikariCP pool exhausts immediately; DB CPU 100%. | **Distributed Mutex (`RLock`)** with **Double-Checked Locking**. Exactly 1 thread queries DB; others wait and read cache. |
| **Cache Penetration** | Clients repeatedly query non-existent keys (e.g. `/product/invalid-999`). | `null` is returned from DB and **never cached**, so every probe hits PostgreSQL unmitigated. | **Empty Sentinel Object** cached in Redis with a short TTL (e.g. 60s) to serve fast 404s without hitting DB. |
| **Invalidation Cascades** | Thousands of keys written in a batch with identical fixed TTL (e.g. 60m). | All keys expire at the exact same second, causing a thundering herd query wave. | **Randomized TTL Jitter** ($T_{base} + \text{rand}(\Delta)$) to flatten expiration boundaries. |

---

### 1. Triggering the Cache Stampede (Vulnerable Naive Route)

The naive endpoint `/api/catalog/hot-deal/naive` checks Redis and on miss fires a heavy multi-table join against PostgreSQL without synchronization.

```bash
# 1. Warm the cache key
curl -i http://localhost:8080/api/catalog/hot-deal/naive

# 2. Simulate key expiry or admin purge
docker exec -it btc-redis redis-cli DEL "catalog::hot-deal"
# (or trigger eviction via HTTP: curl -X POST "http://localhost:8080/api/catalog/evict?key=catalog::hot-deal")

# 3. Fire 50 concurrent virtual threads
# Using the built-in Java virtual thread load test:
./mvnw.cmd test-compile exec:java -Dexec.mainClass="com.btc.nplus1.CacheStampedeLoadTest" -Dexec.args="stampede"

# Or using k6:
k6 run script/cache-stampede.js
```

#### What Happens in Grafana & Logs:
* `hikaricp_connections_pending` jumps immediately to **40+**.
* 50 identical `SELECT` statements flood PostgreSQL.
* Requests queue past HikariCP's `connection-timeout: 1000ms`, throwing connection starvation errors.
* Tail latency ($p99$) explodes over **1,500ms**.

---

### 2. Testing the Solution: Distributed Mutex + Double-Checked Locking

The optimized endpoint `/api/catalog/hot-deal/mutex` guards cache rebuilding using Redisson distributed lock `RLock` and double-checked locking:

```bash
# Invalidate key and fire 50 concurrent virtual threads targeting the mutex route:
curl -X POST "http://localhost:8080/api/catalog/evict?key=catalog::hot-deal"
./mvnw.cmd test-compile exec:java -Dexec.mainClass="com.btc.nplus1.CacheStampedeLoadTest" -Dexec.args="stampede"
```

#### Code Mechanics ([CatalogService.java](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/service/CatalogService.java)):
```java
public ProductCatalogDTO getHotDealWithMutex() {
    // 1. Initial fast-path cache read
    ProductCatalogDTO data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
    if (data != null) return data;

    // 2. Acquire distributed lock for this specific key
    RLock lock = redissonClient.getLock(LOCK_HOT_DEAL_KEY);
    try {
        if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
            try {
                // 3. Double-Checked Locking: Did another thread populate cache while we waited?
                data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
                if (data != null) return data;

                // 4. Exactly one thread executes the heavy query
                data = loadFromDatabase("hot-deal");

                // 5. Write back with randomized TTL jitter (300s + rand(0..60s))
                Duration ttl = Duration.ofSeconds(300 + ThreadLocalRandom.current().nextInt(60));
                redisTemplate.opsForValue().set(HOT_DEAL_KEY, data, ttl);
                return data;
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        } else {
            // Fallback: brief yield and read cache
            Thread.sleep(100);
            return redisTemplate.opsForValue().get(HOT_DEAL_KEY);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return loadFromDatabase("hot-deal");
    }
}
```

#### Verification Under the Microscope:
* **PostgreSQL Statement Count**: **Exactly 1 SQL statement** is executed.
* **HikariCP Pending Queue**: Stays at **0**.
* **Latency ($p99$)**: Drops from $>1,500\text{ms}$ down to **$<20\text{ms}$**.

---

### 3. Testing Cache Penetration vs Empty Sentinels

Querying non-existent products:

```bash
# Naive (Every request queries PostgreSQL):
curl -i http://localhost:8080/api/catalog/product/fake-id-999/naive

# Optimized (First request queries DB, caches sentinel, subsequent queries hit Redis):
curl -i http://localhost:8080/api/catalog/product/fake-id-999/sentinel

# Run benchmark with 50 concurrent virtual threads:
./mvnw.cmd test-compile exec:java -Dexec.mainClass="com.btc.nplus1.CacheStampedeLoadTest" -Dexec.args="penetration"
```

* **Naive**: 50/50 requests hit PostgreSQL.
* **Optimized**: 1 DB miss $\rightarrow$ 49 sentinel hits (`cache_gets_total{result="sentinel"}`) served directly from Redis in 0.6ms.

---

## 📊 Grafana Dashboards

Grafana is pre-configured with two dashboards under the **Break The Code** folder:
* **URL**: `http://localhost:3000`
* **Credentials**: `admin` / `admin`

### 1. Episode 3 Dashboard: Cache Stampede, Penetration & Invalidation Cascades
* **Dashboard File**: [`grafana-dashboard/grafana-caching.json`](file:///C:/practice/nplus1/grafana-dashboard/grafana-caching.json)
* **Panels**:
  1. **Cache Observability**: Hits (`cache_gets_total{result="hit"}`) vs Misses (`result="miss"`) vs Sentinel Protections (`result="sentinel"`).
  2. **HikariCP**: Connection Pool Starvation & Pending Queue Contention (`hikaricp_connections_pending`).
  3. **Application**: HTTP $p95$ and $p99$ Tail Latency by Route.
  4. **Redisson**: Distributed Lock Acquisition Wait Time (`cache_lock_acquire_seconds`).
  5. **Application**: Catalog Endpoints Throughput (req/sec).
  6. **Service Layer**: `@Observed` Method Execution Latency ($p95$).

### 2. Episode 2 Dashboard: Offset vs Keyset Pagination
* **Dashboard File**: [`grafana-dashboard/grafana-pagination.json`](file:///C:/practice/nplus1/grafana-dashboard/grafana-pagination.json)
* Compares read amplification factor, buffer cache churn, and deep offset degradation.

---

## 🔍 Distributed Traces in Grafana Tempo

1. Open Grafana (`http://localhost:3000`) $\rightarrow$ **Explore** $\rightarrow$ select **Tempo**.
2. Run TraceQL query:
   ```traceql
   { resource.service.name = "nplus1" }
   ```
3. Compare the waterfall spans:
   * **Stampede Trace (Naive)**: Every concurrent trace contains an active JDBC span querying PostgreSQL.
   * **Stampede Trace (Mutex)**:
     - **Winning Thread**: Shows span `lock:tryLock` $\rightarrow$ DB Query $\rightarrow$ Redis `SET`.
     - **Waiting Threads**: Shows span `lock:tryLock` wait $\rightarrow$ Redis `GET` cache hit (0.4ms) with **no database span**.

---

## 🧪 Episode 1 & 2 Reference Endpoints

### Episode 1: N+1 Query Problem
```bash
# Vulnerable N+1 (50 extra queries)
curl "http://localhost:8080/api/orders?strategy=nplus1&limit=50"

# Join Fetch solution (1 query)
curl "http://localhost:8080/api/orders?strategy=joinfetch&limit=50"

# EntityGraph solution (1 query)
curl "http://localhost:8080/api/orders?strategy=entitygraph&limit=50"
```

### Episode 2: Deep Pagination Strategies
```bash
# Deep Offset (Scans and discards 10,000 rows)
curl "http://localhost:8080/api/orders/offset?page=500&size=20"

# Keyset Pagination (Direct index seek (created_at, id) < (cursor_date, cursor_id))
curl "http://localhost:8080/api/orders/keyset?size=20"

# Deferred Join (Narrow index scan with delayed join)
curl "http://localhost:8080/api/orders/deferred?page=500&size=20"
```
