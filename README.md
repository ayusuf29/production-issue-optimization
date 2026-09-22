# Spring Boot Distributed Caching: Stampede, Penetration & Observability

This repository demonstrates the three critical production caching vulnerabilities and their enterprise architectural solutions in Spring Boot:
1. **Cache Stampede (Dogpiling / Thundering Herd)**: Solved via **Distributed Mutex (`RLock`)** with **Double-Checked Locking**.
2. **Cache Penetration**: Solved via **Empty Sentinel Object Caching** with short TTL.
3. **Invalidation Cascades**: Solved via **Randomized TTL Jitter** ($T_{\text{base}} + \text{rand}(\Delta)$).

The project includes an end-to-end **Observability Stack** (Metrics, Distributed Tracing, and Dashboards) built with **Redis 7**, **PostgreSQL 16**, **Prometheus**, **OpenTelemetry Collector**, **Grafana Tempo**, **Redis Exporter**, and **Grafana**.

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
                                      │               │                        │              │
                                      │  ┌────────────┴───────────┐            │              │
                                      │  │ Redisson RLock Client  │            │              │
                                      │  │ & RedisTemplate (JSON) │            │              │
                                      │  └────────────┬───────────┘            │              │
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
                                                        │   Distributed Mutex    │
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

Verify all 7 containers are healthy and running:

```bash
docker compose ps
```

| Container | Service | Port | Description |
| :--- | :--- | :--- | :--- |
| `btc-postgres` | PostgreSQL 16 | `5432` | Primary database (`ecommerce`, user: `postgres`, pass: `password`) |
| `btc-redis` | Redis 7 | `6379` | In-memory cache & Redisson distributed lock manager |
| `btc-redis-exporter` | Redis Exporter | `9121` | Prometheus exporter for Redis operations, latency & memory |
| `btc-prometheus` | Prometheus | `9090` | Time-series metrics engine (scrapes Spring Boot, OTel & Redis) |
| `btc-tempo` | Grafana Tempo | `3200` | Distributed tracing storage |
| `btc-otel-collector` | OpenTelemetry Collector | `4317` (gRPC), `4318` (HTTP), `8889` (Prometheus) | Receives traces & collects engine telemetry |
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

The application starts on port `8080`.  
On startup, [CatalogDataSeeder.java](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/seed/CatalogDataSeeder.java) automatically initializes categories, products, and technical specifications (`hot-deal`, `prod-1001`).

---

## 🎯 The Three Production Caching Pitfalls

```
1. Cache Stampede (Dogpiling)           2. Cache Penetration               3. Invalidation Cascade
┌───────────────────────────────┐       ┌───────────────────────────┐      ┌───────────────────────────┐
│ Hot key expires under load    │       │ Probing non-existent IDs  │      │ Mass keys expire together │
│ 50 threads get null           │       │ DB returns null           │      │ Static TTL: all die at T0 │
│ 50 threads query DB at once   │       │ Null is never cached      │      │ DB hit by a sudden wave   │
│ Pool starvation & HTTP 500s   │       │ 100% queries hit DB       │      │ Latency cliff across app  │
└───────────────┬───────────────┘       └─────────────┬─────────────┘      └─────────────┬─────────────┘
                ▼                                     ▼                                  ▼
      [ Distributed Mutex ]                 [ Empty Sentinel ]                     [ TTL Jitter ]
       Redisson RLock + DCL                  Placeholder in Redis                 T_base + rand(delta)
```

---

## 🔬 Deep-Dive & Step-by-Step Reproduction

### 1. Cache Stampede (Dogpiling)

#### The Problem
When a high-traffic cache entry (e.g. Black Friday `hot-deal`) expires or is evicted, dozens or hundreds of concurrent threads observe a cache miss simultaneously. Each thread independently queries PostgreSQL with complex joins to rebuild the cache entry. Because HikariCP has a constrained pool (`maximum-pool-size: 10`, `connection-timeout: 1000ms`), the database connection pool is depleted instantly, triggering query queuing, connection acquisition timeouts, and HTTP 500/504 errors.

#### Trigger the Stampede (Naive Implementation)
The naive endpoint [`/api/catalog/hot-deal/naive`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/controller/CatalogController.java) implements basic cache-aside without locking:

```bash
# 1. Warm the cache key
curl -i http://localhost:8080/api/catalog/hot-deal/naive

# 2. Simulate key expiry or purge
curl -X POST "http://localhost:8080/api/catalog/evict?key=catalog::hot-deal"
# Or using Redis CLI: docker exec -it btc-redis redis-cli DEL "catalog::hot-deal"

# 3. Fire 50 concurrent virtual threads at the exact same millisecond
./mvnw.cmd test-compile exec:java -Dexec.mainClass="com.btc.nplus1.CacheStampedeLoadTest" -Dexec.args="stampede"
```

#### Observable Symptoms in Grafana:
* **HikariCP Pending Queue**: `hikaricp_connections_pending` spikes to **40+**.
* **Cache Misses**: 50 identical misses logged simultaneously.
* **HTTP Latency**: $p99$ explodes past **1,500ms**, with connection timeout errors.

---

### 2. The Solution: Distributed Mutex (`RLock`) + Double-Checked Locking

#### The Architectural Fix
The optimized endpoint [`/api/catalog/hot-deal/mutex`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/service/CatalogService.java) wraps cache regeneration in a distributed mutex using Redisson's `RLock` combined with **Double-Checked Locking**:

```java
public ProductCatalogDTO getHotDealWithMutex() {
    // 1. Initial fast-path cache read
    ProductCatalogDTO data = redisTemplate.opsForValue().get("catalog::hot-deal");
    if (data != null) return data;

    // 2. Acquire distributed lock for this specific key
    RLock lock = redissonClient.getLock("lock::catalog::hot-deal");
    try {
        // Wait up to 2 seconds; lease lock for 5 seconds
        if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
            try {
                // 3. Double-Checked Locking: Did another thread build it while we waited?
                data = redisTemplate.opsForValue().get("catalog::hot-deal");
                if (data != null) return data;

                // 4. Exactly one thread executes the heavy query
                data = loadFromDatabase("hot-deal");

                // 5. Write back with randomized TTL jitter (300s + rand(0..60s))
                Duration ttl = Duration.ofSeconds(300 + ThreadLocalRandom.current().nextInt(60));
                redisTemplate.opsForValue().set("catalog::hot-deal", data, ttl);
                return data;
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        } else {
            // Fallback: brief yield and read cache populated by lock holder
            Thread.sleep(100);
            return redisTemplate.opsForValue().get("catalog::hot-deal");
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return loadFromDatabase("hot-deal");
    }
}
```

#### Verify the Fix Under Load:
```bash
# Invalidate key and fire 50 concurrent virtual threads targeting the mutex route:
curl -X POST "http://localhost:8080/api/catalog/evict?key=catalog::hot-deal"
./mvnw.cmd test-compile exec:java -Dexec.mainClass="com.btc.nplus1.CacheStampedeLoadTest" -Dexec.args="stampede"
```

#### Results Under the Microscope:
* **PostgreSQL Statements**: **Exactly 1 query** is executed across all 50 concurrent requests.
* **HikariCP Pending Connections**: Remains flat at **0**.
* **Success Rate**: **100% (50/50 HTTP 200)**.
* **Tail Latency ($p99$)**: Drops from $>1,500\text{ms}$ down to **$<20\text{ms}$**.

---

### 3. Cache Penetration & Empty Sentinel Protection

#### The Problem
When malicious probes or crawler bots query IDs that do not exist (e.g. `/api/catalog/product/invalid-999`), the database returns `null`. Standard cache-aside does not store `null` in Redis, causing **100% of subsequent requests** for non-existent items to hit PostgreSQL directly.

#### The Solution: Empty Sentinel Object
When the database returns `null`, write an empty sentinel DTO (`ProductCatalogDTO.emptySentinel()`) to Redis with a short TTL (e.g. 60s):

```bash
# Naive (Every single request queries PostgreSQL):
curl -i http://localhost:8080/api/catalog/product/fake-id-999/naive

# Optimized with Sentinel (First query hits DB, subsequent queries hit Redis):
curl -i http://localhost:8080/api/catalog/product/fake-id-999/sentinel

# Run benchmark with 50 concurrent virtual threads:
./mvnw.cmd test-compile exec:java -Dexec.mainClass="com.btc.nplus1.CacheStampedeLoadTest" -Dexec.args="penetration"
```

* **Naive Benchmark**: 50/50 requests execute database queries.
* **Sentinel Benchmark**: 1 DB check $\rightarrow$ 49 sentinel cache hits (`cache_gets_total{result="sentinel"}`) returned from Redis in **0.4ms**.

---

### 4. Invalidation Cascades & TTL Jitter

#### The Problem
Setting an identical TTL (e.g. 5 minutes) across batches of cache keys causes all entries to expire at the exact same instant, recreating a widespread database stampede at regular intervals.

#### The Solution: Randomized TTL Jitter
Add a bounded random jitter to the base TTL:
$$\text{TTL} = T_{\text{base}} + \text{rand}(0, \Delta)$$

```java
Duration ttl = Duration.ofSeconds(300 + ThreadLocalRandom.current().nextInt(60));
redisTemplate.opsForValue().set(key, value, ttl);
```

This distributes expiration events smoothly across a continuous time window, eliminating periodic query spikes.

---

## 📊 Grafana Observability Dashboard

Grafana is pre-provisioned with the dedicated Caching dashboard:
* **URL**: `http://localhost:3000`
* **Credentials**: `admin` / `admin`
* **Path**: **Dashboards** $\rightarrow$ **Break The Code** $\rightarrow$ **Break The Code — Episode 3: Cache Stampede, Penetration & Invalidation Cascades**
* **Dashboard JSON**: [`grafana-dashboard/grafana-caching.json`](file:///C:/practice/nplus1/grafana-dashboard/grafana-caching.json)

### Dashboard Panels & PromQL Metrics

| Panel | Metric / PromQL | Description |
| :--- | :--- | :--- |
| **1. Cache Traffic & Hits/Misses** | `sum(rate(cache_gets_total{result=~"hit\|miss\|sentinel"}[15s])) by (result)` | Visualizes cache hit ratio, stampede miss spikes, and sentinel interception rate. |
| **2. HikariCP Pool Starvation** | `hikaricp_connections_pending` vs `hikaricp_connections_active` | Displays connection starvation (spikes to 40+ during stampede, stays at 0 under mutex). |
| **3. HTTP Tail Latency (p95 / p99)** | `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{uri=~"/api/catalog.*"}[15s])) by (le, uri))` | Compares latency profiles: naive routes explode over 1.5s while mutex routes remain flat. |
| **4. Redisson Lock Wait Time** | `rate(cache_lock_acquire_seconds_sum[15s]) / clamp_min(rate(cache_lock_acquire_seconds_count[15s]), 0.001)` | Measures average time threads wait to acquire distributed lock before double-checked read. |
| **5. Route Throughput** | `sum(rate(http_server_requests_seconds_count{uri=~"/api/catalog.*"}[15s])) by (uri)` | Real-time requests/second across naive vs mutex/sentinel endpoints. |
| **6. Service Method Latency** | `histogram_quantile(0.95, sum(rate({__name__=~"catalog_service_.*_seconds_bucket"}[15s])) by (le, __name__))` | Fine-grained internal method execution time tracked via Micrometer `@Observed`. |

---

## 🔍 Distributed Tracing with Grafana Tempo

1. Open Grafana (`http://localhost:3000`) $\rightarrow$ **Explore** $\rightarrow$ select **Tempo**.
2. Run the TraceQL query:
   ```traceql
   { resource.service.name = "nplus1" }
   ```
3. Compare the waterfall spans during a cache cold start:
   * **Stampede Trace (Naive)**: Every concurrent request shows an active JDBC child span executing `SELECT p.*, c.name FROM products...`.
   * **Stampede Trace (Mutex)**:
     - **Thread 1 (Lock Winner)**: `catalog.service.hotdeal.mutex` $\rightarrow$ acquired lock $\rightarrow$ executed 1 JDBC query $\rightarrow$ Redis `SET`.
     - **Threads 2–50 (Waiters)**: Brief lock wait span $\rightarrow$ Redis `GET` cache hit ($<1\text{ms}$) with **zero JDBC database spans**.

---

## 🧪 Load Testing Tools Included

### 1. Java Virtual Thread Benchmark
Dispatches 50 concurrent virtual threads at the exact same millisecond:
```bash
# Run both Stampede and Penetration benchmarks:
./mvnw.cmd test-compile exec:java -Dexec.mainClass="com.btc.nplus1.CacheStampedeLoadTest"

# Run only Stampede benchmark:
./mvnw.cmd test-compile exec:java -Dexec.mainClass="com.btc.nplus1.CacheStampedeLoadTest" -Dexec.args="stampede"

# Run only Penetration benchmark:
./mvnw.cmd test-compile exec:java -Dexec.mainClass="com.btc.nplus1.CacheStampedeLoadTest" -Dexec.args="penetration"
```

### 2. k6 Load Testing Script
Simulates cache invalidation and virtual user dogpiling:
```bash
# Run against naive endpoint:
k6 run -e TARGET=naive script/cache-stampede.js

# Run against mutex endpoint:
k6 run -e TARGET=mutex script/cache-stampede.js
```

---

## 🛠️ Operational CLI Commands

```bash
# Evict hot deal cache key via Redis CLI
docker exec -it btc-redis redis-cli DEL "catalog::hot-deal"

# Check key TTL (demonstrating TTL jitter)
docker exec -it btc-redis redis-cli TTL "catalog::hot-deal"

# Inspect Redis keys and memory usage
docker exec -it btc-redis redis-cli INFO stats

# Restart Grafana to reload dashboard configurations
docker compose restart grafana

# Stop all containers
docker compose down

# Stop containers and wipe volumes (full clean slate)
docker compose down -v
```
