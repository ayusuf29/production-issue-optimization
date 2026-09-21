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
            log.warn("Cache MISS for '{}'. Loading directly from database without mutex...", HOT_DEAL_KEY);
            data = loadFromDatabase("hot-deal");
            redisTemplate.opsForValue().set(HOT_DEAL_KEY, data, Duration.ofMinutes(5));
        } else {
            hitCounter.increment();
        }
        return data;
    }

    /**
     * Optimized Mutex Locking + Double-Checked Locking + TTL Jitter implementation:
     * Neutralizes Cache Stampede by allowing only 1 thread to rebuild the cache entry.
     */
    @Observed(name = "catalog.service.hotdeal.mutex", contextualName = "get-hot-deal-mutex")
    public ProductCatalogDTO getHotDealWithMutex() {
        // 1. Initial fast-path cache read
        ProductCatalogDTO data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
        if (data != null) {
            hitCounter.increment();
            return data;
        }

        // 2. Acquire distributed lock for this specific cache key
        RLock lock = redissonClient.getLock(LOCK_HOT_DEAL_KEY);
        long lockWaitStart = System.currentTimeMillis();
        try {
            // Wait up to 2 seconds to acquire; lease lock for 5 seconds to prevent deadlocks
            if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
                lockTimer.record(System.currentTimeMillis() - lockWaitStart, TimeUnit.MILLISECONDS);
                try {
                    // 3. Double-Checked Locking: Did another thread populate the cache while we waited?
                    data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
                    if (data != null) {
                        hitCounter.increment();
                        return data;
                    }

                    // 4. Exactly one thread executes the heavy query
                    missCounter.increment();
                    log.info("Acquired distributed lock! Refreshing cold cache for '{}'...", HOT_DEAL_KEY);
                    data = loadFromDatabase("hot-deal");

                    // 5. Write back to cache with randomized TTL jitter (e.g., 300s + rand(0..60s))
                    Duration ttl = Duration.ofSeconds(300 + ThreadLocalRandom.current().nextInt(60));
                    redisTemplate.opsForValue().set(HOT_DEAL_KEY, data, ttl);
                    return data;
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                // Fallback strategy: wait briefly and retry reading from cache
                Thread.sleep(100);
                data = redisTemplate.opsForValue().get(HOT_DEAL_KEY);
                if (data != null) {
                    hitCounter.increment();
                    return data;
                }
                missCounter.increment();
                return loadFromDatabase("hot-deal");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            missCounter.increment();
            return loadFromDatabase("hot-deal"); // Safe fallback under thread interruption
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
            log.warn("Cache MISS for product '{}'. Querying DB directly...", productId);
            data = loadProductFromDb(productId);
            if (data != null) {
                redisTemplate.opsForValue().set(cacheKey, data, Duration.ofMinutes(30));
            }
            // Vulnerability: when data is null, NOTHING is stored in Redis!
        } else {
            hitCounter.increment();
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
                return null;
            }
            hitCounter.increment();
            return data;
        }

        RLock lock = redissonClient.getLock(LOCK_PRODUCT_PREFIX + productId);
        try {
            if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
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
                        // Cache empty placeholder with a short TTL (60 seconds) to block malicious probes
                        redisTemplate.opsForValue().set(cacheKey, ProductCatalogDTO.emptySentinel(), Duration.ofSeconds(60));
                        return null;
                    }

                    // Cache real product with TTL Jitter (30m + rand(0..120s))
                    Duration ttl = Duration.ofSeconds(1800 + ThreadLocalRandom.current().nextInt(120));
                    redisTemplate.opsForValue().set(cacheKey, data, ttl);
                    return data;
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                Thread.sleep(100);
                data = redisTemplate.opsForValue().get(cacheKey);
                return (data != null && data.isEmptySentinel()) ? null : data;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loadProductFromDb(productId);
        }
    }

    // =========================================================================
    // DATABASE HELPER & ARTIFICIAL WORKLOAD SIMULATION
    // =========================================================================

    @Transactional(readOnly = true)
    public ProductCatalogDTO loadFromDatabase(String productId) {
        // Simulate complex joins, pricing rules, inventory validation, and db query latency (35-45ms)
        try {
            Thread.sleep(40);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        return productRepository.findProductWithDetailsById(productId)
                .map(this::mapToDto)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public ProductCatalogDTO loadProductFromDb(String productId) {
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
    }
}
