# **Break The Code — Episode 3: Caching — Stampede, Penetration & Invalidation Cascades**

* **Target Runtime:** ~20–24 minutes  
* **Screen Setup:** Left side = Terminal (mvnw, load generator) & IDE (IntelliJ IDEA); Right side = Grafana Dashboard, Prometheus Metrics, & PostgreSQL Connection Monitors.  
* **Real-World Analogy:** The E-Commerce Storefront Counter vs. The 50,000m² Deep Warehouse.
* **Real-World E-Commerce Condition:** Black Friday Flash Sales, Midnight Price Drops, Competitor Scraping Bots, and ERP Midnight Catalog Syncs.

---

### **[00:00 - 03:00] Cold Open: The Storefront Counter vs. The 50,000m² Deep Warehouse**

**Visual:** Camera on host face, cutting rapidly to a live Grafana dashboard displaying a sharp spike in HikariCP pending connections (red line), 20 HTTP 500 connection timeouts, and database CPU surging while Redis sits idle.

&nbsp;

**Audio (Speaker):** 
"Welcome back to Break The Code. Today, we are tackling the silent killer of distributed systems: **high-traffic caching failures**."

&nbsp;

"If you are new to caching, think of an e-commerce operation like an actual retail storefront backed by a gigantic warehouse:

* **Redis (L2 Cache) is the Quick-Reference Clipboard at the Front Cashier Counter.**  
  When a customer asks for a product price or stock, the cashier checks the clipboard. It takes **0.5 milliseconds (2 seconds in human time)**. Instant response.
* **PostgreSQL is the 50,000-square-meter Deep Warehouse 1 Kilometer Away.**  
  If the item isn't on the cashier's clipboard, a warehouse employee must put on a hard hat, walk 1 kilometer into the back aisles, search through pallets, join supplier pricing and tax tables, and walk back. It takes **80 milliseconds (10 minutes in human time)**.
* **HikariCP Connection Pool is the Doorway with Exactly 10 Warehouse Workers.**  
  You only have 10 licensed workers with forklifts who can enter the deep warehouse at any one time (`maximum-pool-size: 10`).

&nbsp;

"In this episode, we expose the three catastrophic caching failures, connect them to real-world e-commerce scenarios, reproduce them live under load, and apply production-grade engineering fixes in Spring Boot 3, Redis, and Redisson:
1. **The Cache Stampede (Dogpiling)**
2. **Cache Penetration (The Phantom Item Attack)**
3. **Invalidation Cascades (The Thundering Herd)**"

---

### **[03:00 - 07:00] Anatomies of Failure: Real E-Commerce Scenarios & Warehouse Analogies**

**Visual:** Split-screen animation comparing the physical retail store counter against a high-traffic e-commerce architecture (e.g. Tokopedia / Amazon / Shopify).

&nbsp;

#### **1. The Cache Stampede (Dogpiling / Thundering Herd)**
* **Real-World E-Commerce Scenario:**  
  It is 00:00:00 on Black Friday. The **"Midnight Flash Deal: RTX 5090 GPU"** price updates from $1,999 to $999. The cache key `catalog::hot-deal` is invalidated or expires.  
  At **00:00:01, 50,000 eager shoppers simultaneously click 'Refresh'** on the product page.
* **The Warehouse Store Analogy:**  
  At 10:00 AM, the cashier's clipboard entry for the flash deal laptop expires. At 10:00:01 AM, **50 customers arrive at the exact same millisecond** asking for the laptop's price.
* **Naive Code Flaw:**  
  The cashier doesn't see the price on the clipboard. So instead of checking with 1 worker, the cashier panics and sends **all 50 customers' requests sprinting into the deep warehouse at once**!
* **The Disaster (Live Live Live!):**  
  Only 10 warehouse workers exist (`HikariCP max-pool-size = 10`).  
  - Workers 1 to 10 take 80ms each.  
  - Requests 11 to 20 wait 160ms.  
  - Requests 21 to 30 wait 240ms.  
  - Requests 31 to 50 are forced to wait longer than the fail-fast timeout (`connection-timeout: 250ms`).  
  **HikariCP connection pool starves, throwing `Connection is not available, request timed out after 250ms`! The website crashes with HTTP 500 / 504 errors for 40% of shoppers!**

&nbsp;

#### **2. Cache Penetration (The Phantom Item / Scraper Bot Attack)**
* **Real-World E-Commerce Scenario:**  
  A rogue competitor or SEO scraping bot begins systematically probing URLs with randomized or non-existent IDs:  
  `GET /api/catalog/product/invalid-uuid-999`  
  `GET /api/catalog/product/random-string-abc`  
  None of these products exist in the catalog.
* **The Warehouse Store Analogy:**  
  A mischievous customer repeatedly walks up to the counter and asks for `invalid-uuid-999` (*"Magic Flying Carpet"*).
* **Naive Code Flaw:**  
  1. Cashier checks clipboard: Item not found.
  2. Cashier sends a worker into the warehouse.
  3. Worker spends 25ms searching every shelf: *"We don't sell that item."*
  4. Cashier tells the customer: *"404 Not Found."*  
  **The fatal flaw: Because the product was null, the cashier wrote NOTHING on the clipboard!**
* **The Disaster:**  
  One second later, another bot request asks for the exact same non-existent product. Since the clipboard is still blank, the cashier dispatches **another worker to the deep warehouse**.  
  If bots fire 10,000 requests for fake IDs, **100% of those requests bypass Redis completely and slam PostgreSQL with pointless index scans**, exhausting CPU and I/O!

&nbsp;

#### **3. Invalidation Cascades & The Thundering Herd**
* **Real-World E-Commerce Scenario:**  
  Every night at 02:00 AM, the enterprise ERP (SAP / NetSuite) runs a catalog sync job that updates 50,000 product prices, setting a standard fixed expiration: `TTL = Duration.ofMinutes(60)`.
* **The Warehouse Store Analogy:**  
  The store manager writes 10,000 products onto the clipboard, marking every single one to expire at exactly 03:00:00 AM.
* **The Disaster:**  
  At 03:00:00 AM sharp, all 50,000 products evaporate from Redis simultaneously. The very next wave of shoppers causes tens of thousands of concurrent database cache misses at the exact same millisecond.

---

### **[07:00 - 12:00] Step-by-Step Fixes: Fast Mutex, Empty Sentinels, and Jitter**

**Visual:** IntelliJ IDEA editing `CatalogService.java`.

&nbsp;

#### **Fix 1: Distributed Mutex (`RLock`) + Double-Checked Locking + Spin-Wait**
* **The Warehouse Fix:** When the laptop expires from the clipboard and 50 customers arrive, the cashier gives a **single "Warehouse Pass" (Distributed Lock)** to Worker #1.  
  Customers #2 through #50 are told: *"Please wait 15 milliseconds at the counter."*
* **Why Naive Mutex Queuing Fails (The 950ms Trap):**  
  If 49 waiting threads line up serially to acquire and release the lock one-by-one, each Redis lock round-trip takes 18ms. 50 threads * 18ms = **950ms**!  
  **The Enterprise Fix:** Only **ONE** thread grabs the lock to rebuild. The other 49 threads immediately spin-poll Redis in parallel. As soon as Worker #1 writes the price (at 85ms), all 49 waiting threads read Redis concurrently in 2ms!

```java
public ProductCatalogDTO getHotDealWithMutex() {
    // 1. Fast-path check in Redis (<1ms)
    ProductCatalogDTO data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
    if (data != null) {
        hitCounter.increment();
        return data;
    }

    // 2. Try to acquire the distributed lock immediately (waitTime = 0)
    RLock lock = redissonClient.getLock(LOCK_HOT_DEAL_KEY);
    boolean acquired = false;
    try {
        acquired = lock.tryLock(0, 5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return loadFromDatabase("hot-deal");
    }

    if (acquired) {
        try {
            // 3. Double-Checked Locking: Did another thread write it just before we locked?
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
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    } else {
        // 6. Concurrency Optimization: Other 49 threads spin-poll Redis concurrently!
        for (int i = 0; i < 30; i++) {
            try { Thread.sleep(15); } catch (InterruptedException ignored) {}
            data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
            if (data != null) {
                hitCounter.increment();
                return data;
            }
        }
        return loadFromDatabase("hot-deal");
    }
}
```

&nbsp;

#### **Fix 2: Empty Sentinel Caching (Neutralizing Penetration)**
* **The Warehouse Fix:** When Worker #1 returns saying *\"Magic Flying Carpet 999 does not exist\"*, the cashier slaps a **Red Sticky Note (Empty Sentinel)** on the clipboard:  
  `"Magic Flying Carpet 999 = DOES_NOT_EXIST (Expires in 60s)"`.
* When the subsequent 9,999 scraper bot requests arrive, the cashier glances at the red sticky note and returns `404 Not Found` in **0.5 milliseconds**, completely shielding PostgreSQL!

```java
if (data == null) {
    // Cache empty sentinel placeholder with a short 60s TTL
    redisTemplate.opsForValue().set(cacheKey, ProductCatalogDTO.emptySentinel(), Duration.ofSeconds(60));
    return null;
}
```

&nbsp;

#### **Fix 3: TTL Jitter (Flattening Cascades)**
* Instead of setting every key to expire at exactly 300 seconds, add random noise:  
  `Duration.ofSeconds(300 + ThreadLocalRandom.current().nextInt(60));`  
* Keys expire smoothly across a 60-second window, turning a destructive tsunami into a gentle trickle.

---

### **[12:00 - 17:00] Live Benchmark & What to Observe on Grafana**

**Visual:** Left terminal running `./mvnw.cmd "exec:java" "-Dexec.classpathScope=test" "-Dexec.mainClass=com.btc.nplus1.CacheStampedeLoadTest" "-Dexec.args=all"`; Right side showing Grafana dashboard at `http://localhost:3000/d/btc-caching-monitor`.

&nbsp;

**Audio (Speaker):**
"Let's fire our virtual thread load harness with 50 concurrent requests against both implementations and look at the real production numbers."

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
  Under Naive, the red **Pending Threads** line spikes to 20+ as threads starve waiting for connections. Under Mutex, pending threads stays at **0**.
* **Panel 3 (Tail Latency):**  
  Under Naive, latency spikes to 377ms with 20 failures. Under Mutex, P99 latency flattens to **104ms** with a 100% success rate.

---

### **[17:00 - End] The Production Checklist & Wrap-Up**

**Visual:** Host on camera with the 4 Golden Rules summary graphic.

&nbsp;

**Audio (Speaker):**
"Here is your enterprise caching checklist to survive Black Friday and high-traffic drops:
1. **Never allow concurrent misses for hot keys:** Protect them with a non-blocking distributed lock (`RLock.tryLock(0, ...)`), double-checked locking, and parallel cache polling.
2. **Cache non-existence:** When an ID is missing, store an Empty Sentinel with a short TTL (30–60s) to defuse scraper bot attacks.
3. **Always apply TTL Jitter:** Add a 5–15% random delta to prevent midnight ERP batch jobs from triggering an invalidation cascade.
4. **Monitor your connection pools:** Alert on `hikaricp_connections_pending > 5` to catch cache stampedes before they cause customer-facing downtime.

Keep breaking the code so production doesn't break you!"
