# **Break The Code — Episode 3: Caching — Stampede, Penetration & Invalidation Cascades**

* **Target Runtime:** \~20–24 minutes  
* &nbsp;  
* **Screen Setup:** Left side \= Terminal (redis-cli, k6 load generator) & IDE (IntelliJ IDEA); Right side \= Grafana Dashboard, Prometheus Metrics, & PostgreSQL Connection Monitors.  
* &nbsp;

### **\[00:00 \- 02:00\] Cold Open & The Production Outage**

**Visual:** Camera on host face, cutting rapidly to a live Grafana dashboard displaying a sharp, jagged drop in Redis CPU to 0% while PostgreSQL CPU shoots to 100% and HikariCP connection latency spikes vertically.

&nbsp;

**Audio (Speaker):** "Welcome back to Break The Code. In Episode 2, we showed how deep-offset pagination quietly evicts your database buffer cache. Today, we are tackling a critical production scenario: caching systems under high load."

&nbsp;

"The standard caching tutorial is familiar: check Redis, return the value if present; otherwise, fetch from PostgreSQL, write to Redis with a 5-minute TTL, and return. It passes staging, handles baseline traffic effortlessly, and appears production-ready."

&nbsp;

"Until Black Friday, a flash-sale launch, or a marketing push hits. A single hot catalog key expires, or an admin purges an outdated price. Instantly, hundreds of concurrent virtual threads experience a cache miss at the exact same millisecond. Instead of protecting your database, Redis steps out of the way, and a stampede of identical, heavy SQL queries hammers PostgreSQL."

&nbsp;

"Your HikariCP pool exhausts in milliseconds, database CPU maxes out, read replicas lag, and your checkout gateway begins throwing HTTP 504 Gateway Timeouts. This is the **Cache Stampede**, often compounded by **Cache Penetration** and **Invalidation Cascades**."

&nbsp;

(Screen: Transition to CatalogService.java in IntelliJ IDEA)

&nbsp;

"Let's look at the naive code that creates this vulnerability:"

&nbsp;

Java

@GetMapping("/api/catalog/hot-deal")

public ProductCatalogDTO getHotDeal() {

&nbsp;&nbsp;&nbsp;&nbsp;ProductCatalogDTO data \= redisTemplate.opsForValue().get("catalog::hot-deal");

&nbsp;&nbsp;&nbsp;&nbsp;if (data \== null) {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;data \= loadFromDatabase(); // Heavy join across 5 tables

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;redisTemplate.opsForValue().set("catalog::hot-deal", data, Duration.ofMinutes(5));

&nbsp;&nbsp;&nbsp;&nbsp;}

&nbsp;&nbsp;&nbsp;&nbsp;return data;

}

&nbsp;

"Clean, readable, and fundamentally unsafe under concurrent load. Let's spin up our harness and trigger the failure."

&nbsp;

### **\[02:00 \- 05:00\] The Observability Stack & Triggering the Stampede**

**Visual:** Split-screen showing docker-compose.yaml on the left, and a dedicated Grafana Dashboard on the right.

&nbsp;

**Audio (Speaker):**

"Our environment simulates real-world contention under strict, constrained limits:"

(Screen: Scroll through docker-compose.yaml and application.yml)

* **PostgreSQL 16:** Running complex join queries against a 200,000-item catalog with constrained worker memory.  
* **Redis 7 (Alpine):** In-memory key-value store acting as our L2 cache.  
* **Spring Boot 3.x:** Configured with Java 21 virtual threads, meaning the application tier can easily spawn hundreds of concurrent runners without thread exhaustion.  
* **HikariCP:** Maximum pool size capped strictly at 10 connections to expose pool exhaustion rapidly.  
* **Telemetry Panels on Grafana:**  
  1. cache\_gets\_total{result="miss"} & cache\_gets\_total{result="hit"} (Prometheus counter).  
  2. hikaricp\_connections\_pending (Connection pool starvation gauge).  
  3. postgresql\_cpu\_usage vs redis\_cpu\_usage.  
  4. HTTP Tail Latency (P99 / P95).

"Let's prime the cache first."

&nbsp;

(Screen: Run curl \-i http://localhost:8080/api/catalog/hot-deal)

&nbsp;

"Key is warm. Redis latency is 0.8ms. HikariCP pending connections sit at 0."

"Now, let's inject chaos. We simulate a key expiration or an explicit eviction while simultaneously firing 50 concurrent virtual threads via our load test tool targeting /api/catalog/hot-deal."

&nbsp;

(Screen: Terminal executes the trigger command and fires the load test)

&nbsp;

Bash

redis-cli DEL "catalog::hot-deal"

\# Run load test with 50 concurrent virtual threads targeting /api/catalog/hot-deal

&nbsp;

"Watch Grafana immediately\!"

&nbsp;

(Screen: Focus on Grafana dashboard metrics shifting violently)

&nbsp;

* hikaricp\_connections\_pending jumps immediately to 40+.  
* cache\_gets\_total{result="miss"} records a sharp vertical spike.  
* Redis CPU drops to nearly zero because it simply served 50 fast nil responses.  
* PostgreSQL CPU surges to 98%, and incoming HTTP requests start queueing and failing our 1,000ms connection timeout threshold.

### **\[05:00 \- 08:30\] Anatomies of Failure: Stampede, Penetration, and Cascades**

**Visual:** Motion graphics diagram showing thread interactions between Redis, Spring Boot instances, and PostgreSQL.

&nbsp;

**Audio (Speaker):**

"Why did this collapse occur? Let's break down the three distinct caching vulnerabilities present in this system."

&nbsp;

#### **1\. The Cache Stampede (Dogpiling)**

"When key catalog::hot-deal disappears, 50 virtual threads query Redis concurrently. All 50 receive null. Because there is no concurrency control at the application tier, all 50 threads independently conclude: *'I must refresh this data.'* They simultaneously fire 50 identical, expensive join queries into PostgreSQL. The database connection pool is immediately starved, causing request latency to explode."

&nbsp;

#### **2\. Cache Penetration**

"What happens if an attacker queries non-existent IDs, such as /api/catalog/product/invalid-uuid-999? The application checks Redis: key does not exist. It checks PostgreSQL: row does not exist. Since no record is found, the naive service writes nothing back to Redis.

&nbsp;

Every subsequent request for that non-existent entity bypasses the cache entirely and penetrates straight to the database. At scale, this functions as an unauthenticated Denial of Service."

&nbsp;

#### **3\. Invalidation Cascades & The Thundering Herd**

"Imagine setting a batch of 10,000 product keys to expire with a fixed TTL: Duration.ofMinutes(60). Exactly 60 minutes later, all 10,000 keys expire at the exact same second. The cache hit ratio drops off a cliff, and scheduled background workers or live customer traffic trigger a massive multi-query stampede known as an Invalidation Cascade."

&nbsp;

### **\[08:30 \- 13:00\] Step-by-Step Fix: Mutex Locking & Double-Checked Locking**

**Visual:** Full-screen IntelliJ IDEA editing CatalogService.java. Introducing Redisson and standardizing synchronization.

&nbsp;

**Audio (Speaker):**

"To resolve the cache stampede, we must enforce a strict invariant: **only one execution thread is permitted to rebuild a cold cache entry at any given moment.** All other threads must wait, yield, and read the populated value."

&nbsp;

"In a distributed system with multiple application replicas, local Java synchronized blocks or ReentrantLock won't suffice. We need a distributed mutex. We'll use Redisson's RLock."

&nbsp;

(Screen: Host walks through code line by line)

&nbsp;

Java

public ProductCatalogDTO getCatalogWithMutex() {

&nbsp;&nbsp;&nbsp;&nbsp;// 1\. Initial fast-path cache read

&nbsp;&nbsp;&nbsp;&nbsp;ProductCatalogDTO data \= redisTemplate.opsForValue().get("catalog::hot-deal");

&nbsp;&nbsp;&nbsp;&nbsp;if (data \!= null) return data;

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;// 2\. Acquire distributed lock for this specific cache key

&nbsp;&nbsp;&nbsp;&nbsp;RLock lock \= redissonClient.getLock("lock::catalog::hot-deal");

&nbsp;&nbsp;&nbsp;&nbsp;try {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Wait up to 2 seconds to acquire; lease lock for 5 seconds to prevent deadlocks

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// 3\. Double-Checked Locking: Did another thread populate the cache while we waited?

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;data \= redisTemplate.opsForValue().get("catalog::hot-deal");

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;if (data \!= null) return data;

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// 4\. Exactly one thread executes the heavy query

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;data \= loadFromDatabase();

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// 5\. Write back to cache with randomized TTL jitter

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Duration ttl \= Duration.ofSeconds(300 \+ ThreadLocalRandom.current().nextInt(60));

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;redisTemplate.opsForValue().set("catalog::hot-deal", data, ttl);

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;return data;

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;} else {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Fallback strategy: wait briefly and retry reading from cache

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Thread.sleep(100);

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;return redisTemplate.opsForValue().get("catalog::hot-deal");

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;}

&nbsp;&nbsp;&nbsp;&nbsp;} catch (InterruptedException e) {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Thread.currentThread().interrupt();

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;return loadFromDatabase(); // Safe fallback under thread interruption

&nbsp;&nbsp;&nbsp;&nbsp;} finally {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;if (lock.isHeldByCurrentThread()) {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;lock.unlock();

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;}

&nbsp;&nbsp;&nbsp;&nbsp;}

}

&nbsp;

(Screen: Highlight the Double-Checked Locking section)

&nbsp;

"Pay close attention to step 3: **Double-Checked Locking**. If threads B, C, and D are waiting behind thread A's lock acquisition, once thread A completes the write and releases the lock, thread B acquires it next. Without checking Redis a second time inside the lock, thread B would execute the redundant database query anyway. Always check the cache once before locking, and once immediately inside the acquired lock."

&nbsp;

### **\[13:00 \- 16:15\] Mitigating Penetration and Invalidation Cascades**

**Visual:** Showing empty sentinel handling in IntelliJ, followed by Redis TTL jitter diagram.

&nbsp;

**Audio (Speaker):**

"With the mutex in place, let's address Cache Penetration and Cascading Expirations."

&nbsp;

#### **1\. Neutralizing Penetration via Empty Sentinels**

"When a query for a non-existent record reaches the database, do not return early with a no-op. Cache a sentinel object:"

&nbsp;

Java

public ProductCatalogDTO getProductById(String productId) {

&nbsp;&nbsp;&nbsp;&nbsp;String cacheKey \= "catalog::product::" \+ productId;

&nbsp;&nbsp;&nbsp;&nbsp;ProductCatalogDTO data \= redisTemplate.opsForValue().get(cacheKey);

&nbsp;&nbsp;&nbsp;&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;if (data \!= null) {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;return data.isEmptySentinel() ? null : data;

&nbsp;&nbsp;&nbsp;&nbsp;}

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;// Acquire lock and load

&nbsp;&nbsp;&nbsp;&nbsp;data \= loadProductFromDb(productId);

&nbsp;&nbsp;&nbsp;&nbsp;if (data \== null) {

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Cache empty placeholder with a short TTL (e.g., 60 seconds)

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;redisTemplate.opsForValue().set(cacheKey, ProductCatalogDTO.emptySentinel(), Duration.ofSeconds(60));

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;return null;

&nbsp;&nbsp;&nbsp;&nbsp;}

&nbsp;&nbsp;&nbsp;&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;redisTemplate.opsForValue().set(cacheKey, data, Duration.ofMinutes(30));

&nbsp;&nbsp;&nbsp;&nbsp;return data;

}

&nbsp;

"Now, if a bot generates 100,000 requests for non-existent IDs, only the initial request for each unique ID reaches Postgres. The remaining 99,999 hit the 60-second sentinel in Redis and return a fast 404."

&nbsp;

#### **2\. Eliminating Cascades with TTL Jitter**

"Look back at our mutex code: Notice how we set the TTL: Duration.ofSeconds(300 \+ ThreadLocalRandom.current().nextInt(60))."

&nbsp;

"By injecting a randomized variance—or *jitter*—into cache durations, keys that were populated together gradually expire across an extended time window rather than all at once. This flattens the invalidation spike into a manageable background trickle."

&nbsp;

### **\[16:15 \- 19:30\] Verification: Under the Microscope**

**Visual:** Split-screen running the reproduction trigger and load test again, watching Grafana and PostgreSQL logs side-by-side.

&nbsp;

**Audio (Speaker):**

"Let's put the patch to the test. We'll execute the exact same command to delete our hot deal key, and immediately unleash our 50 virtual threads."

&nbsp;

(Screen: Terminal executes commands)

&nbsp;

Bash

redis-cli DEL "catalog::hot-deal"

\# Run load test with 50 concurrent virtual threads targeting /api/catalog/hot-deal

&nbsp;

"Look at the database log output on the left\!"

(Screen: Terminal displays PostgreSQL query log)

&nbsp;

Plaintext

2026-09-16 10:45:01.102 UTC \[32\] LOG:  statement: SELECT p.\*, c.name FROM products p JOIN categories c ... WHERE p.id \= 'hot-deal'

\# Exactly 1 statement executed

&nbsp;

"Look at that: **Exactly 1 SQL query hits PostgreSQL.**"

&nbsp;

(Screen: Switch view to Grafana metrics)

* hikaricp\_connections\_pending stays at **0**.  
* Database CPU maintains a steady baseline without spiking.  
* Thread 1 acquires the lock, loads data in 45ms, and updates Redis.  
* The remaining 49 threads wait on the lock, detect the refreshed cache entry on their double-check or sleep-retry path, and return immediately.  
* P99 latency drops from over 1,500ms down to 18ms under load.

### **\[19:30 \- End\] Summary & What's Next**

**Visual:** Camera on host, cutting to a summary checklist on screen.

&nbsp;

**Audio (Speaker):**

"Let's wrap up the production rules of enterprise caching:

&nbsp;

* **Never let concurrent misses reach the database unchecked:** Guard high-traffic hot keys with distributed mutexes (such as Redisson RLock) and always implement double-checked locking.  
* **Cache non-existence:** Prevent cache penetration by storing empty sentinels with short, conservative TTLs.  
* **Always apply TTL jitter:** Add a randomized delta (e.g., 5–15% variance) to your base expiration times to eliminate thundering-herd cascades.  
* **Observe your metrics:** Keep alerts on hikaricp\_connections\_pending and monitor cache hit-to-miss ratios to catch stampedes before they exhaust your infrastructure."

"All sample implementations, Redisson configurations, and Grafana dashboard templates are available in the GitHub repository linked below."

&nbsp;

"In Episode 4, we tackle **Connection Leakage and Transaction Boundaries under Async Runtimes**. Subscribe, leave your questions in the comments, and keep breaking the code so production doesn't break you. See you next time\!"