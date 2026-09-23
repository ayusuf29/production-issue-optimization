# **Break The Code — Episode 3: Caching — Stampede, Penetration & Invalidation Cascades**

* **Target Runtime:** ~24–28 minutes  
* **Screen Setup:** Left side = Terminal (`mvnw`, virtual thread load harness) & IDE (IntelliJ IDEA); Right side = Grafana Dashboard, Prometheus Metrics, & PostgreSQL Connection Monitors.  
* **Real-World Analogy:** The E-Commerce Storefront Counter vs. The 50,000m² Deep Warehouse.  
* **Real-World E-Commerce Condition:** The "Works Fine Until Black Friday" Paradox — Black Friday Flash Sales, Midnight RTX 5090 Price Drops, Aggressive Scraping Bots, and ERP Midnight Catalog Syncs.  

---

### **[00:00 - 03:30] Cold Open: The Storefront Counter vs. The 50,000m² Deep Warehouse**

**Visual:** Camera on host face, cutting rapidly to a live Grafana dashboard displaying a catastrophic spike in HikariCP pending connections (red line), 20 HTTP 500 connection timeouts, and database CPU hitting 100% while Redis sits idle.

&nbsp;

**Audio (Speaker):**  
"Welcome back to Break The Code. Today, we are exposing the silent killer of distributed architectures: **high-traffic caching failures**."

&nbsp;

"Every junior and mid-level engineer thinks caching is trivial. You write five lines of code:  
`if (cached != null) return cached; else { data = db.find(); cache.put(data); return data; }`  
You deploy it. It passes code review. Staging tests pass with flying colors. For six months in production, CPU is at 5%, response times are 2 milliseconds, and management thinks you're a hero.

Then comes **Black Friday Midnight**. And your entire e-commerce platform disintegrates in under 4 seconds."

&nbsp;

#### **The Physical Retail Analogy: Counter vs. Warehouse**
"To understand why this happens, picture your e-commerce platform as an actual physical retail department store backed by a massive industrial warehouse:

* **Redis (L2 In-Memory Cache) is the Quick-Reference Clipboard at the Front Cashier Counter.**  
  When a shopper asks for a product price or inventory count, the cashier checks the clipboard. It takes **0.5 milliseconds (2 seconds in human time)**. Instantaneous.
* **PostgreSQL is the 50,000-Square-Meter Deep Warehouse 1 Kilometer Away.**  
  If the item is not on the cashier's clipboard, a warehouse employee has to strap on steel-toed boots, walk 1 kilometer down dusty aisles, climb a ladder, cross-reference inventory with supplier manifests, and walk back. It takes **80 milliseconds (10 minutes in human time)**.
* **HikariCP Connection Pool is the Doorway with Exactly 10 Warehouse Workers.**  
  You only have 10 licensed warehouse employees equipped with forklifts (`maximum-pool-size: 10`). At any given millisecond, a maximum of 10 people can step into that deep warehouse."

&nbsp;

---

### **[03:30 - 08:30] The Commercial Reality: Why Normal Days Hide The Flaw (The Black Friday Cliff)**

**Visual:** Split-screen motion graphic: Left shows "Normal Tuesday Traffic (Uniform Distribution)", Right shows "Black Friday Flash Sale (Extreme Hot-Key Pareto Skew)".

&nbsp;

**Audio (Speaker):**  
"Let's answer the billion-dollar question: **Why does naive caching survive everyday traffic, but instantly explodes during flash sales?**"

&nbsp;

#### **1. The Normal Day Illusion: Uniform Traffic & High Cache Hit Ratio**
* **Traffic Pattern:** On a normal Tuesday afternoon, your store handles 100 requests per second spread evenly across **10,000 different catalog items** (books, socks, cables, kitchen knives).
* **Why it hides bugs:**  
  1. The cache hit ratio sits comfortably at 95%+.  
  2. Cache misses are temporally and spatially scattered. Maybe once every 5 seconds, key `prod-412` expires. A single thread queries PostgreSQL (taking 80ms) and repopulates Redis.  
  3. Out of your 10 HikariCP connections, only 1 connection is briefly used. The other 9 sit idle.  
  4. Your monitoring dashboards stay green. You assume your caching strategy is bulletproof.

&nbsp;

#### **2. The Black Friday Trigger: The Extreme Hot-Key Pareto Skew**
* **The Commercial Trigger:**  
  At 00:00:00 on Black Friday, marketing launches a mega flash deal: **"Midnight Drop: RTX 5090 GPU at 70% Off ($599 instead of $1,999) — Limited to 100 Units"**.
* **The Traffic Shift:**  
  1. Traffic surges by 50x–100x (from 100 req/sec to 10,000+ req/sec).  
  2. More critically, the access distribution shifts from a wide long-tail distribution to an **extreme Pareto / Power Law distribution (Hot Key skew)**.  
  3. **90% of all incoming shopper traffic is targeting the EXACT SAME product ID** (`catalog::hot-deal`).
* **The Catastrophic Moment (The Cliff):**  
  Everything runs smoothly while the key is in Redis... **until the 5-minute TTL expires**, or the inventory service updates the remaining stock count and evicts `catalog::hot-deal`.  
  At **00:00:01.000**, the key disappears from Redis.  
  At **00:00:01.001**, **5,000 concurrent threads** ask for `catalog::hot-deal`.
* **The Resulting Carnage:**  
  Because naive cache-aside has no locking:
  1. All 5,000 threads see a cache miss at the exact same millisecond.
  2. All 5,000 threads simultaneously race to PostgreSQL.
  3. HikariCP only has 10 connections. Threads 1 to 10 grab them.  
  4. The other 4,990 threads queue up in memory.  
  5. After waiting 250ms (`connection-timeout: 250ms`), **hundreds of threads time out, throwing `SQLTransientConnectionException` and HTTP 500 errors to shoppers!**
  6. Shoppers panic, hit browser refresh, and multiply the stampede. Database CPU hits 100%, cascading into your payment and checkout services. You lose hundreds of thousands of dollars in the first 60 seconds of the sale!

&nbsp;

#### **The Three Production Caching Disasters We Will Solve Today:**
1. **The Cache Stampede (Dogpiling / Thundering Herd):** Concurrent misses on expired hot keys choke the connection pool.
2. **Cache Penetration (The Phantom Item / Scraper Bot Attack):** Rogue scrapers or malicious bots querying non-existent product IDs that bypass Redis completely and drill directly into the database.
3. **Invalidation Cascades:** Nightly ERP batch jobs synchronizing catalog items with identical fixed TTLs, causing thousands of keys to expire at the exact same second.

---

### **[08:30 - 16:30] Deep-Dive: The Core Architectural Concepts**

**Visual:** Technical architecture diagrams highlighting Redisson, Distributed Mutex, Double-Checked Locking (DCL), and Empty Sentinels.

&nbsp;

**Audio (Speaker):**  
"Before we jump into [`CatalogService.java`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/service/CatalogService.java), let's clearly define the four engineering weapons we use to make our cache immune to Black Friday stampedes:"

&nbsp;

```
+----------------------------------------------------------------------------------------------------+
|                               THE HIGH-TRAFFIC REQUEST PIPELINE                                    |
|                                                                                                    |
|   Shopper Request                                                                                  |
|        │                                                                                           |
|        ▼                                                                                           |
|   [ 1. Check Redis ] ──(Cache Hit: Normal Product)──────────────► Return Data (<1ms)               |
|        │                                                                                           |
|        ├─────────────(Cache Hit: Empty Sentinel)───────────────► Return 404 Immediately (<0.5ms)  |
|        ▼ (Cache Miss)                                                                              |
|   [ 2. Redisson Distributed Mutex (tryLock 0s) ]                                                   |
|        ├──(Acquired Lock: Thread #1)──► [ 3. Double-Checked Locking (DCL) ]                       |
|        │                                     ├──(Found in Cache)──► Unlock & Return (<1ms)         |
|        │                                     └──(Still Missing)───► Query DB (80ms)               |
|        │                                                                  │                        |
|        │                                                                  ▼                        |
|        │                                                            Write Redis + TTL Jitter       |
|        │                                                                  │                        |
|        │                                                                  ▼                        |
|        │                                                               Unlock                      |
|        │                                                                                           |
|        └──(Lock Busy: Threads #2..50)─► [ 4. Non-Blocking Spin-Poll Redis ]                        |
|                                              └──(Loop 15ms x 30)──► Read Warm Cache in Parallel!   |
+----------------------------------------------------------------------------------------------------+
```

&nbsp;

#### **Concept 1: Redisson — Distributed Java Services Framework (Architecture & Microservice Topology)**

##### **1. What is Redisson?**
While standard Spring Data Redis or Jedis treat Redis merely as a raw key-value store (`GET`, `SET`), **Redisson** is an ultra-high-performance distributed services framework built on top of Netty.  
It provides distributed, thread-safe implementations of familiar Java standard library data structures and concurrency primitives (`RLock`, `RSemaphore`, `RReadWriteLock`, `RCountDownLatch`).

##### **2. Architectural Clarification: Is Redisson a Standalone Microservice or a Client Library?**
A common misconception among developers is:  
*\"Is Redisson an independent microservice sitting in front of other services (like Checkout, Payment, Catalogue), or does each service talk to an independent 'Redis Service'?\"*

**The short answer: Redisson is NOT a microservice. Redisson is an in-process Java client library (SDK / JAR dependency) embedded directly inside each microservice.**

Here is the exact architectural topology:

```
❌ ANTI-PATTERN: The "Independent Caching Microservice" (DO NOT DO THIS)
+-----------------------+   +-----------------------+   +-----------------------+
|    Catalog Service    |   |   Checkout Service    |   |    Payment Service    |
+-----------┬-----------+   +-----------┬-----------+   +-----------┬-----------+
            │ REST / gRPC               │ REST / gRPC               │ REST / gRPC
            └─────────────────► ┌───────▼───────────────┐ ◄─────────┘
                                │ Caching Microservice  │  <-- Single Point of Failure!
                                │  (HTTP Proxy Layer)   │  <-- Adds 10-30ms latency!
                                └───────┬───────────────┘  <-- Destroys Lock Thread-Affinity!
                                        │ Raw TCP
                                        ▼
                                ┌───────────────┐
                                │ Redis Cluster │
                                └───────────────┘

--------------------------------------------------------------------------------------

✅ PRODUCTION ARCHITECTURE: In-Process Redisson Client Embedded in Each Domain
+--------------------------------+  +--------------------------------+  +--------------------------------+
|       Catalog Service          |  |       Checkout Service         |  |        Payment Service         |
|  ┌──────────────────────────┐  |  |  ┌──────────────────────────┐  |  |  ┌──────────────────────────┐  |
|  | RedissonClient (In-Proc) |  |  |  | RedissonClient (In-Proc) |  |  |  | RedissonClient (In-Proc) |  |
|  └────────────┬─────────────┘  |  |  └────────────┬─────────────┘  |  |  └────────────┬─────────────┘  |
+───────────────┼────────────────+  +───────────────┼────────────────+  +───────────────┼────────────────+
                │ Netty TCP (<0.5ms)                │ Netty TCP (<0.5ms)                │ Netty TCP (<0.5ms)
                ▼                                   ▼                                   ▼
+────────────────────────────────────────────────────────────────────────────────────────────────────────+
|                             Distributed Redis Cluster / AWS ElastiCache                                |
|  - Namespace: catalog::product::{id}         - Namespace: checkout::cart::{id}                         |
|  - Lock: lock::catalog::hot-deal             - Lock: lock::checkout::order::{id}                       |
+────────────────────────────────────────────────────────────────────────────────────────────────────────+
```

##### **Why an "Independent Caching Microservice" in Front is a Fatal Anti-Pattern:**
1. **Latency Multiplication (Kills the whole point of Redis):**  
   A direct Netty TCP round-trip to Redis via Redisson takes **0.3 to 0.8 milliseconds**.  
   If you put an independent HTTP/gRPC microservice in between:  
   `Catalog -> HTTP serialization & network hop (8ms) -> Cache Service -> Redis (0.5ms) -> Cache Service -> HTTP response (8ms) -> Catalog`.  
   You just inflated your cache latency from **<1ms to 20ms+**!
2. **Breaks Distributed Locking Thread-Affinity & Redisson Watchdog:**  
   In Java, [`RLock.tryLock()`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/service/CatalogService.java#L104) and `unlock()` are bound to the calling thread's identifier (`Thread.currentThread().getId()`). The **Watchdog renewal daemon** tracks the local thread executing the database query. If locking is proxied over a stateless HTTP microservice, the lock owner is lost, and auto-renewal becomes impossible or vulnerable to race conditions!
3. **Single Point of Failure (SPOF) & Bottleneck:**  
   If all traffic from Catalog, Checkout, and Payment funnels through a single "Cache Service" gateway, that service becomes an enormous CPU and thread-pool bottleneck that can take down the entire company storefront.
4. **How Multiple Microservices Share Redis Safely:**  
   Each service (Catalog, Order, Payment) includes `redisson-spring-boot-starter` in its `pom.xml` and configures its own in-process [`RedissonClient`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/config/RedisConfig.java#L23-L33). Isolation is achieved either via **strict domain key prefixes** (`catalog::*`, `order::*`, `payment::*`) on a shared Redis Cluster, or via **dedicated Redis clusters per domain** (e.g., Catalog Redis optimized for high-read LRU cache; Checkout Redis optimized for persistence and distributed locks).

##### **3. Under the Hood (Lua Scripting & The Watchdog Daemon):**
1. Redisson executes **Redis Lua scripts** to ensure atomic lock acquisition (`SET key uuid NX PX 30000`). Checking if the lock exists, setting the owner, and setting the lease time happen in a single, atomic Redis operation.  
2. Redisson includes a built-in **Watchdog timer**. If your business logic takes longer than expected, the background watchdog automatically extends the lock lease every 10 seconds. If your pod suddenly crashes or suffers a kernel panic, the lock naturally expires, eliminating the risk of permanent distributed deadlocks!

&nbsp;

#### **Concept 2: Distributed Mutex (Mutual Exclusion)**
* **What is a Mutex?**  
  Mutex stands for **Mutual Exclusion**. It is a synchronization primitive that guarantees that among hundreds or thousands of concurrent competing threads across multiple pods, **at most ONE single thread can execute the critical section at any given time**.
* **Role in Cache Stampede Prevention:**  
  When the hot deal key expires, instead of letting all 50 threads rush into the database warehouse, the distributed mutex acts as a strict **single-entry pass**. Only the thread holding the `RLock` is allowed to query PostgreSQL.
* **The "950ms Trap" vs. Our Non-Blocking Spin-Poll Optimization:**  
  Many developers implement a naive distributed mutex by queuing threads:  
  `lock.lock()` (blocking wait).  
  *Why is that disastrous?* If 50 threads queue up serially, and each thread takes 18ms to acquire, check, and release the lock round-trip, the 50th thread will wait **950 milliseconds**!  
  **The Enterprise Solution in [`CatalogService`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/service/CatalogService.java#L101-L157):**  
  Use `lock.tryLock(0, 5, TimeUnit.SECONDS)`. Thread #1 acquires the lock and queries PostgreSQL. Threads #2 through #50 fail lock acquisition immediately with zero wait time, enter a fast **non-blocking spin-poll loop (sleeping 15ms)**, and read the newly warmed cache concurrently the moment Thread #1 writes it!

&nbsp;

#### **Concept 3: DCL (Double-Checked Locking)**
* **What is Double-Checked Locking?**  
  Adapted from high-performance multithreaded singleton patterns, DCL ensures that expensive operations are checked **both before and after acquiring a lock**.
* **Why is DCL Indispensable in Caching?**  
  Consider this timeline between Thread A and Thread B:  
  1. **T0:** Both Thread A and Thread B check Redis. Both see a miss.  
  2. **T1:** Thread A wins the race and acquires the distributed mutex. Thread B waits or re-tries.  
  3. **T2:** Thread A queries PostgreSQL (80ms), writes the result to Redis, and releases the lock.  
  4. **T3:** Thread B now acquires the lock.  
  *If Thread B did not check Redis again, it would execute the exact same heavy SQL query that Thread A just finished!*  
* **The Double-Check Mechanism:**  
  - **Check 1 (Fast-Path, Unlocked):** Read Redis. 99% of traffic returns in <1ms without touching the lock.  
  - **Acquire Mutex:** Only miss-path threads contend.  
  - **Check 2 (Inside Mutex):** The very first instruction inside the `if (acquired)` block is:  
    `data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);`  
    `if (data != null) return data;`  
  If another thread warmed the cache just before this thread acquired the lock, DCL catches it immediately, skips PostgreSQL entirely, and returns the cached object!

&nbsp;

#### **Concept 4: Empty Sentinel Caching (Null Object Pattern)**
* **What is an Empty Sentinel?**  
  An **Empty Sentinel** (or Null Object placeholder) is a designated lightweight tombstone object—such as [`ProductCatalogDTO.emptySentinel()`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/dto/ProductCatalogDTO.java#L39-L46) with `id: "__EMPTY_SENTINEL__"` and `sentinel: true`—stored directly in Redis when a queried entity does not exist in PostgreSQL.
* **The Vulnerability it Solves: Cache Penetration:**  
  Competitor pricing scrapers and malicious bots often scan your API with randomized, expired, or bogus product IDs (`/api/catalog/product/invalid-uuid-999`).  
  In naive cache-aside, if PostgreSQL returns `null`, the application writes **nothing** to Redis.  
  *The fatal flaw:* Because nothing was cached, the very next request for `invalid-uuid-999` bypasses Redis again and forces PostgreSQL to run another useless B-Tree index scan. If a bot sends 10,000 bogus requests, **100% of them hit the database**!
* **How Sentinels Neutralize Penetration:**  
  1. When PostgreSQL returns `null`, we write an Empty Sentinel into Redis with a **short TTL (e.g. 60 seconds)**.  
  2. The next 9,999 bot requests hit Redis, detect the sentinel flag, and immediately return `404 Not Found` in **0.5 milliseconds**, completely shielding PostgreSQL!  
  3. Why a short 60-second TTL? If an administrator creates that product in the catalog a minute later, the sentinel expires promptly, allowing the real product to be cached.

&nbsp;

---

### **[16:30 - 20:00] Production Code Walkthrough**

**Visual:** IntelliJ IDEA highlighting the implementation in [`CatalogService.java`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/service/CatalogService.java) and configuration in [`RedisConfig.java`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/config/RedisConfig.java).

&nbsp;

#### **1. The Stampede Buster: Fast Mutex + DCL + Spin-Poll**
```java
// Located in CatalogService.java
@Observed(name = "catalog.service.hotdeal.mutex", contextualName = "get-hot-deal-mutex")
public ProductCatalogDTO getHotDealWithMutex() {
    // 1. Initial fast-path cache read (Check 1 - No lock overhead)
    ProductCatalogDTO data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
    if (data != null) {
        hitCounter.increment();
        return data;
    }

    // 2. Try to acquire Redisson distributed lock immediately (waitTime = 0)
    RLock lock = redissonClient.getLock(LOCK_HOT_DEAL_KEY);
    boolean acquired = false;
    try {
        // Lease lock for 5s to prevent deadlock if rebuilder thread dies
        acquired = lock.tryLock(0, 5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return loadFromDatabase("hot-deal");
    }

    if (acquired) {
        try {
            // 3. Double-Checked Locking (Check 2): Did someone warm it while we acquired?
            data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
            if (data != null) {
                hitCounter.increment();
                return data;
            }

            // 4. Exactly ONE thread queries PostgreSQL
            missCounter.increment();
            data = loadFromDatabase("hot-deal");

            // 5. Store in Redis with TTL Jitter (300s + rand(0..60s))
            Duration ttl = Duration.ofSeconds(300 + ThreadLocalRandom.current().nextInt(60));
            redisTemplate.opsForValue().set(HOT_DEAL_KEY, data, ttl);
            return data;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    } else {
        // 6. Concurrency Optimization: Other 49 threads spin-poll Redis in parallel!
        for (int i = 0; i < 30; i++) {
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return loadFromDatabase("hot-deal");
            }
            data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
            if (data != null) {
                hitCounter.increment();
                return data;
            }
        }
        // Safe fallback
        return loadFromDatabase("hot-deal");
    }
}
```

&nbsp;

#### **2. The Penetration Buster: Empty Sentinel Caching**
```java
// Located in CatalogService.java
@Observed(name = "catalog.service.product.sentinel", contextualName = "get-product-sentinel")
public ProductCatalogDTO getProductWithSentinel(String productId) {
    String cacheKey = PRODUCT_KEY_PREFIX + productId;
    ProductCatalogDTO data = redisTemplate.opsForValue().get(cacheKey);

    // Fast-path: Check if cached data is an Empty Sentinel
    if (data != null) {
        if (data.isEmptySentinel()) {
            sentinelCounter.increment();
            return null; // Shield PostgreSQL! Immediate 404
        }
        hitCounter.increment();
        return data;
    }

    // Acquire lock and load from DB
    RLock lock = redissonClient.getLock(LOCK_PRODUCT_PREFIX + productId);
    // ... tryLock & DCL ...
    data = loadProductFromDb(productId);
    if (data == null) {
        // Product does not exist -> Cache Empty Sentinel with short 60s TTL
        redisTemplate.opsForValue().set(cacheKey, ProductCatalogDTO.emptySentinel(), Duration.ofSeconds(60));
        return null;
    }
    // Normal product caching with jitter
    redisTemplate.opsForValue().set(cacheKey, data, Duration.ofSeconds(1800 + ThreadLocalRandom.current().nextInt(120)));
    return data;
}
```

&nbsp;

---

### **[20:00 - 24:00] Live Benchmark & What to Observe on Grafana**

**Visual:** Left terminal running `./mvnw.cmd "exec:java" "-Dexec.classpathScope=test" "-Dexec.mainClass=com.btc.nplus1.CacheStampedeLoadTest" "-Dexec.args=all"`; Right side showing Grafana dashboard at `http://localhost:3000/d/btc-caching-monitor`.

&nbsp;

**Audio (Speaker):**  
"Now let's unleash our virtual thread load harness simulating 50 concurrent shoppers hitting an expired key, and watch the live production metrics."

&nbsp;

#### **1. Cache Stampede Live Results (50 Concurrent Virtual Threads)**
```text
=========================================================================
 EXPERIMENT 1: CACHE STAMPEDE (50 Concurrent Threads on Expired Key)
=========================================================================

>>> SCENARIO: NAIVE CACHE-ASIDE (All 50 threads hit DB)
 Total Wave Duration: 377 ms
 Successful (HTTP 200/404): 30 / 50
 [!] CONNECTION TIMEOUT / 500 ERROR: 20 / 50 requests failed!
 Latency (min/p50/p95/p99/max): 97 / 322 / 371 / 377 / 377 ms
 Root Cause: 50 threads overwhelmed the 10-connection pool. Threads waiting > 250ms timed out!

>>> SCENARIO: DISTRIBUTED MUTEX + DCL (Only 1 thread hits DB)
 Total Wave Duration: 104 ms    <-- 3.6x FASTER WAVE!
 Successful (HTTP 200/404): 50 / 50
 Failures / Timeouts: 0 (100% Healthy)
 Latency (min/p50/p95/p99/max): 96 / 100 / 103 / 104 / 104 ms
 Database Impact: Exactly 1 SQL query executed! Zero connection starvation!
```

#### **2. Cache Penetration Live Results (50 Concurrent Probes for Non-Existent ID)**
```text
=========================================================================
 EXPERIMENT 2: CACHE PENETRATION (Repeat Probes for Non-Existent Product)
=========================================================================

>>> SCENARIO: NAIVE PENETRATION (Null is never cached; 100% hit DB)
 Total Wave Duration: 188 ms
 Successful (HTTP 200/404): 50 / 50
 Latency (min/p50/p95/p99/max): 68 / 173 / 185 / 188 / 188 ms
 Database Impact: 50 pointless database queries executed against PostgreSQL!

>>> SCENARIO: OPTIMIZED SENTINEL (Sentinel cached; 100% hit Redis in <1ms)
 Total Wave Duration: 23 ms     <-- 8x FASTER! (p50: 8 ms)
 Successful (HTTP 200/404): 50 / 50
 Latency (min/p50/p95/p99/max): 3 / 8 / 20 / 23 / 23 ms
 Database Impact: ZERO queries to PostgreSQL! Redis served all 50 requests!
```

&nbsp;

#### **Grafana Dashboard Metric Inspection:**
* **Panel 1 (Hits vs Misses vs Sentinel):**  
  Watch the purple line rise to **49 Sentinel Hits** during the penetration attack, showing the scraper bots were blocked right at Redis.
* **Panel 2 (HikariCP Pool Starvation):**  
  Under Naive, the red **Pending Threads** line spikes to 20+ as threads starve waiting for connections. Under Mutex, pending threads stays pinned at **0**.
* **Panel 3 (Tail Latency):**  
  Under Naive, latency spikes to 377ms with 20 failures. Under Mutex, P99 latency flattens to **104ms** with a 100% success rate.

---

### **[24:00 - End] The Production Checklist & Wrap-Up**

**Visual:** Host on camera with the 4 Golden Rules summary graphic.

&nbsp;

**Audio (Speaker):**  
"Here is your senior engineer caching checklist to survive Black Friday flash sales and bot attacks:

1. **Protect Hot Keys with Distributed Mutex + DCL:** Embed [`RedissonClient`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/config/RedisConfig.java#L23-L33) directly in each microservice with non-blocking acquisition (`tryLock(0, ...)`), double-checked locking, and parallel spin-polling so non-lock holders read in parallel.
2. **Never Create a "Caching Microservice" HTTP Proxy:** Keep Redisson in-process. Access Redis directly over Netty TCP to guarantee sub-millisecond latencies and thread-affinity for distributed locks.
3. **Cache Non-Existence with Empty Sentinels:** When an ID is not found, store [`ProductCatalogDTO.emptySentinel()`](file:///C:/practice/nplus1/src/main/java/com/btc/nplus1/dto/ProductCatalogDTO.java#L39-L46) with a short 30–60s TTL to defuse scraper bot penetration.
4. **Always Add TTL Jitter:** Add a randomized 10–20% delta (`Duration.ofSeconds(300 + ThreadLocalRandom.current().nextInt(60))`) to prevent synchronized batch jobs from triggering invalidation cascades.
5. **Monitor Connection Starvation:** Set Prometheus alerts on `hikaricp_connections_pending > 5`. It is the earliest canary in the coal mine that a cache stampede is occurring.

Keep breaking the code so production doesn't break you!"
