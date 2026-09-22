package com.btc.nplus1.service;

import com.btc.nplus1.domain.Product;
import com.btc.nplus1.domain.ProductDetail;
import com.btc.nplus1.dto.ProductCatalogDTO;
import com.btc.nplus1.repository.ProductRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private static final String HOT_DEAL_KEY = "catalog::hot-deal";
    private static final String LOCK_HOT_DEAL_KEY = "lock::catalog::hot-deal";
    private static final String PRODUCT_KEY_PREFIX = "catalog::product::";
    private static final String LOCK_PRODUCT_PREFIX = "lock::catalog::product::";

    private final ProductRepository productRepository;
    private final RedisTemplate<String, ProductCatalogDTO> redisTemplate;
    private final RedissonClient redissonClient;

    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter sentinelCounter;
    private final Timer lockTimer;

    public CatalogService(ProductRepository productRepository,
                          RedisTemplate<String, ProductCatalogDTO> redisTemplate,
                          RedissonClient redissonClient,
                          MeterRegistry meterRegistry) {
        this.productRepository = productRepository;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;

        this.hitCounter = Counter.builder("cache_gets_total")
                .tag("cache", "catalog")
                .tag("result", "hit")
                .description("Total number of cache hits in catalog")
                .register(meterRegistry);

        this.missCounter = Counter.builder("cache_gets_total")
                .tag("cache", "catalog")
                .tag("result", "miss")
                .description("Total number of cache misses in catalog")
                .register(meterRegistry);

        this.sentinelCounter = Counter.builder("cache_gets_total")
                .tag("cache", "catalog")
                .tag("result", "sentinel")
                .description("Total number of sentinel cache hits preventing penetration")
                .register(meterRegistry);

        this.lockTimer = Timer.builder("cache_lock_acquire_seconds")
                .tag("cache", "catalog")
                .description("Time taken waiting to acquire distributed lock")
                .register(meterRegistry);
    }

    // =========================================================================
    // EPISODE 3: TOPIC 1 - CACHE STAMPEDE (HOT DEAL)
    // =========================================================================

    /**
     * Naive cache-aside implementation:
     * Vulnerable to Cache Stampede under concurrent requests when the key expires or is deleted.
     */
    @Observed(name = "catalog.service.hotdeal.naive", contextualName = "get-hot-deal-naive")
    public ProductCatalogDTO getHotDealNaive() {
        ProductCatalogDTO data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
        if (data == null) {
            missCounter.increment();
            log.warn("[CACHE MISS] Key '{}' not in Redis! Querying DB without lock (holding DB connection 80ms)...", HOT_DEAL_KEY);
            data = loadFromDatabase("hot-deal");
            Duration ttl = Duration.ofMinutes(5);
            redisTemplate.opsForValue().set(HOT_DEAL_KEY, data, ttl);
            log.info("[CACHE POPULATED] Key '{}' saved to Redis (TTL: {}s). Content: id={}, name='{}', price=${}",
                    HOT_DEAL_KEY, ttl.toSeconds(), data.getId(), data.getName(), data.getPrice());
        } else {
            hitCounter.increment();
            log.info("[CACHE HIT] Key '{}' found in Redis! Returning cached: '{}' (${}) [DB Bypassed]",
                    HOT_DEAL_KEY, data.getName(), data.getPrice());
        }
        return data;
    }

    /**
     * Optimized Mutex Locking + Fast Non-Blocking Spin Poll + TTL Jitter implementation:
     * - Only ONE single thread acquires the lock and queries PostgreSQL.
     * - Waiting threads do NOT wait in line for the lock serially (which causes 900ms delays).
     *   Instead, they spin-poll the cache concurrently and return in parallel once the key is warm!
     */
    @Observed(name = "catalog.service.hotdeal.mutex", contextualName = "get-hot-deal-mutex")
    public ProductCatalogDTO getHotDealWithMutex() {
        // 1. Initial fast-path cache read
        ProductCatalogDTO data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
        if (data != null) {
            hitCounter.increment();
            log.info("[CACHE HIT (Fast-Path)] Key '{}' served from Redis: '{}' (${})",
                    HOT_DEAL_KEY, data.getName(), data.getPrice());
            return data;
        }

        // 2. Try to acquire the distributed lock immediately
        RLock lock = redissonClient.getLock(LOCK_HOT_DEAL_KEY);
        long lockWaitStart = System.currentTimeMillis();
        boolean acquired = false;

        try {
            // Lease lock for 5 seconds to prevent deadlock if rebuilder crashes
            acquired = lock.tryLock(0, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loadFromDatabase("hot-deal");
        }

        if (acquired) {
            lockTimer.record(System.currentTimeMillis() - lockWaitStart, TimeUnit.MILLISECONDS);
            try {
                // 3. Double-Checked Locking: Did another thread write it just before we locked?
                data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
                if (data != null) {
                    hitCounter.increment();
                    log.info("[CACHE HIT (DCL Check)] Predecessor already warmed '{}'! Serving cached data.", HOT_DEAL_KEY);
                    return data;
                }

                // 4. Exactly one thread executes the heavy database query
                missCounter.increment();
                log.warn("[CACHE MISS - REBUILDER] Acquired lock '{}'! Exactly 1 thread fetching from DB...", LOCK_HOT_DEAL_KEY);
                data = loadFromDatabase("hot-deal");

                // 5. Write back to cache with randomized TTL jitter (300s + rand(0..60s))
                Duration ttl = Duration.ofSeconds(300 + ThreadLocalRandom.current().nextInt(60));
                redisTemplate.opsForValue().set(HOT_DEAL_KEY, data, ttl);
                log.info("[CACHE POPULATED (Mutex)] Rebuilder wrote '{}' to Redis (TTL: {}s). Content: id={}, name='{}', price=${}",
                        HOT_DEAL_KEY, ttl.toSeconds(), data.getId(), data.getName(), data.getPrice());
                return data;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } else {
            // 6. Concurrency optimization: The other 49 threads do NOT queue serially for the lock.
            // They wait for the rebuilder thread to finish, then read the cache concurrently!
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
                    log.info("[CACHE HIT (Spin-Poll)] Key '{}' read from Redis after {}ms spin! Served: '{}'",
                            HOT_DEAL_KEY, (i + 1) * 15, data.getName());
                    return data;
                }
            }
            // Safe fallback if rebuilder died
            missCounter.increment();
            return loadFromDatabase("hot-deal");
        }
    }

    // =========================================================================
    // EPISODE 3: TOPIC 2 - CACHE PENETRATION (EMPTY SENTINELS)
    // =========================================================================

    /**
     * Naive Product lookup:
     * Vulnerable to Cache Penetration when clients query non-existent IDs repeatedly.
     */
    @Observed(name = "catalog.service.product.naive", contextualName = "get-product-naive")
    public ProductCatalogDTO getProductNaive(String productId) {
        String cacheKey = PRODUCT_KEY_PREFIX + productId;
        ProductCatalogDTO data = redisTemplate.opsForValue().get(cacheKey);

        if (data == null) {
            missCounter.increment();
            log.warn("[CACHE MISS (Penetration Vulnerable)] Product '{}' not in Redis. Querying DB directly...", productId);
            data = loadProductFromDb(productId);
            if (data != null) {
                redisTemplate.opsForValue().set(cacheKey, data, Duration.ofMinutes(30));
                log.info("[CACHE POPULATED] Product '{}' saved to Redis.", productId);
            } else {
                log.warn("[PENETRATION RISK] Product '{}' is NULL in DB! Nothing saved to Redis -> Next request will hit DB again!", productId);
            }
        } else {
            hitCounter.increment();
            log.info("[CACHE HIT] Product '{}' found in Redis.", productId);
        }
        return data;
    }

    /**
     * Optimized Product lookup with Empty Sentinel caching:
     * Neutralizes Cache Penetration by caching sentinel placeholders for non-existent entities.
     */
    @Observed(name = "catalog.service.product.sentinel", contextualName = "get-product-sentinel")
    public ProductCatalogDTO getProductWithSentinel(String productId) {
        String cacheKey = PRODUCT_KEY_PREFIX + productId;
        ProductCatalogDTO data = redisTemplate.opsForValue().get(cacheKey);

        if (data != null) {
            if (data.isEmptySentinel()) {
                sentinelCounter.increment();
                log.info("[SENTINEL HIT] Blocked penetration probe for product '{}' via Redis Sentinel! DB protected.", productId);
                return null;
            }
            hitCounter.increment();
            log.info("[CACHE HIT] Product '{}' found in Redis: '{}'", productId, data.getName());
            return data;
        }

        RLock lock = redissonClient.getLock(LOCK_PRODUCT_PREFIX + productId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loadProductFromDb(productId);
        }

        if (acquired) {
            try {
                data = redisTemplate.opsForValue().get(cacheKey);
                if (data != null) {
                    if (data.isEmptySentinel()) {
                        sentinelCounter.increment();
                        return null;
                    }
                    hitCounter.increment();
                    return data;
                }

                missCounter.increment();
                data = loadProductFromDb(productId);
                if (data == null) {
                    Duration sentinelTtl = Duration.ofSeconds(60);
                    redisTemplate.opsForValue().set(cacheKey, ProductCatalogDTO.emptySentinel(), sentinelTtl);
                    log.warn("[SENTINEL CACHED] Product '{}' does not exist in DB! Cached Empty Sentinel in Redis (TTL: {}s) to block penetration.",
                            productId, sentinelTtl.toSeconds());
                    return null;
                }

                Duration ttl = Duration.ofSeconds(1800 + ThreadLocalRandom.current().nextInt(120));
                redisTemplate.opsForValue().set(cacheKey, data, ttl);
                log.info("[CACHE POPULATED] Product '{}' cached in Redis (TTL: {}s).", productId, ttl.toSeconds());
                return data;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } else {
            // Spin-poll cache directly
            for (int i = 0; i < 30; i++) {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return loadProductFromDb(productId);
                }
                data = redisTemplate.opsForValue().get(cacheKey);
                if (data != null) {
                    if (data.isEmptySentinel()) {
                        sentinelCounter.increment();
                        return null;
                    }
                    hitCounter.increment();
                    return data;
                }
            }
            return loadProductFromDb(productId);
        }
    }

    // =========================================================================
    // EPISODE 3: TOPIC 3 - INVALIDATION CASCADES & ERP CATALOG SYNC
    // =========================================================================

    /**
     * Simulates Midnight ERP Catalog Sync Job.
     * Synchronizes a batch of products into Redis.
     * - "naive": Fixed synchronized expiration (e.g. 4 seconds) -> Causes Invalidation Cascade.
     * - "jitter": Randomized expiration with TTL jitter (4s - 12s) -> Defuses Cascade.
     */
    public Map<String, Object> syncErpCatalog(String strategy, int count) {
        boolean useJitter = "jitter".equalsIgnoreCase(strategy);
        int synced = 0;
        List<Map<String, Object>> items = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            String prodId = "prod-" + i;
            ProductCatalogDTO dto = loadProductFromDb(prodId);
            if (dto != null) {
                String cacheKey = PRODUCT_KEY_PREFIX + prodId;
                long ttlSec = useJitter
                        ? 4 + ThreadLocalRandom.current().nextInt(8) // 4 to 11 seconds
                        : 4; // Exactly 4 seconds for all keys!

                Duration ttl = Duration.ofSeconds(ttlSec);
                redisTemplate.opsForValue().set(cacheKey, dto, ttl);
                synced++;
                items.add(Map.of("key", cacheKey, "ttlSeconds", ttlSec));
            }
        }

        log.info("[ERP SYNC - {}] Synchronized {} products to Redis (useJitter={}).",
                strategy.toUpperCase(), synced, useJitter);

        return Map.of(
                "strategy", strategy,
                "syncedCount", synced,
                "useJitter", useJitter,
                "items", items
        );
    }

    // =========================================================================
    // CACHE INSPECTION & ADMIN HELPERS
    // =========================================================================

    public Map<String, Object> inspectKey(String key) {
        Boolean exists = redisTemplate.hasKey(key);
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        ProductCatalogDTO value = redisTemplate.opsForValue().get(key);

        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        result.put("exists", Boolean.TRUE.equals(exists));
        result.put("ttlSeconds", ttl != null ? ttl : -1);
        if (value != null) {
            result.put("isEmptySentinel", value.isEmptySentinel());
            result.put("cachedProduct", value);
        } else {
            result.put("isEmptySentinel", false);
            result.put("cachedProduct", null);
        }
        return result;
    }

    // =========================================================================
    // DATABASE HELPER & ARTIFICIAL WORKLOAD SIMULATION
    // =========================================================================

    @Transactional(readOnly = true)
    public ProductCatalogDTO loadFromDatabase(String productId) {
        // Simulate heavy join across 5 tables, pricing rules, inventory validation under concurrency (80ms)
        try {
            Thread.sleep(80);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        return productRepository.findProductWithDetailsById(productId)
                .map(this::mapToDto)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public ProductCatalogDTO loadProductFromDb(String productId) {
        // Simulate index scan and database round trip (25ms)
        try {
            Thread.sleep(25);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        return productRepository.findProductWithDetailsById(productId)
                .map(this::mapToDto)
                .orElse(null);
    }

    private ProductCatalogDTO mapToDto(Product p) {
        List<String> highlights = new ArrayList<>();
        Map<String, String> specs = new HashMap<>();
        if (p.getDetails() != null) {
            for (ProductDetail d : p.getDetails()) {
                specs.put(d.getAttributeName(), d.getAttributeValue());
                highlights.add(d.getAttributeName() + ": " + d.getAttributeValue());
            }
        }
        return new ProductCatalogDTO(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getPrice(),
                p.getCategory() != null ? p.getCategory().getName() : "Uncategorized",
                p.getStockQuantity(),
                highlights,
                specs
        );
    }

    public void evictKey(String key) {
        redisTemplate.delete(key);
        log.info("[CACHE EVICTED] Key '{}' removed from Redis.", key);
    }
}
