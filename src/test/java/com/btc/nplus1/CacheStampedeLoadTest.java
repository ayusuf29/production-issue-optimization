package com.btc.nplus1;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Break The Code — Episode 3: Cache Stampede, Penetration & Invalidation Cascades Benchmark.
 * Demonstrates:
 * 1. Cache Stampede (The Thundering Herd on a single hot deal key):
 *    - 50 concurrent threads hit an expired key simultaneously.
 *    - Standard: 50 threads hit PostgreSQL at once -> HikariCP pool starvation (>250ms timeout) & HTTP 500!
 *    - Mutex (Fix): Only 1 thread queries PostgreSQL; 49 threads spin-poll Redis in parallel (~85ms).
 * 2. Cache Penetration (Scraper Bot Phantom Item Attack):
 *    - Probing non-existent IDs.
 *    - Standard: 100% of requests hit PostgreSQL every single time.
 *    - Sentinel (Fix): 1st probe caches empty sentinel; subsequent requests return from Redis in <1ms.
 * 3. Invalidation Cascades (Midnight ERP Batch Sync vs TTL Jitter):
 *    - Standard: 20 keys with synchronized fixed TTL expire at the exact same second -> 100% simultaneous DB miss wave!
 *    - Jitter (Fix): Keys expire staggered across a random window -> DB misses smoothed out, 0 pool contention!
 *
 * Usage:
 *   java CacheStampedeLoadTest [scenario] [variant] [duration] [cooldown]
 *
 * Arguments:
 *   scenario : all | stampede | penetration | cascade          (default: all)
 *   variant  : standard | fix | both                          (default: both)
 *   duration : sustained traffic duration in seconds          (default: 30)
 *   cooldown : Prometheus breathing room between runs (s)     (default: 15)
 */
public class CacheStampedeLoadTest {

    private static final String BASE_URL = "http://localhost:8080/api/catalog";
    private static final int CONCURRENCY = 50;
    private static final int DEFAULT_DURATION_SECONDS = 30;
    private static final int DEFAULT_COOLDOWN_SECONDS = 15;

    public static void main(String[] args) throws Exception {
        // Parse arguments: [scenario] [variant] [duration] [cooldown]
        String testType = "all";
        String variant = "both";
        int duration = DEFAULT_DURATION_SECONDS;
        int cooldown = DEFAULT_COOLDOWN_SECONDS;

        if (args.length > 0) {
            testType = args[0].toLowerCase();
        }

        if (args.length > 1) {
            String arg1 = args[1].toLowerCase();
            if (arg1.matches("\\d+")) {
                duration = Integer.parseInt(arg1);
            } else {
                variant = normalizeVariant(arg1);
            }
        }

        if (args.length > 2) {
            String arg2 = args[2].toLowerCase();
            if (arg2.matches("\\d+")) {
                duration = Integer.parseInt(arg2);
            } else {
                variant = normalizeVariant(arg2);
            }
        }

        if (args.length > 3) {
            try {
                cooldown = Integer.parseInt(args[3]);
            } catch (NumberFormatException ignored) {}
        }

        boolean runStandard = "both".equals(variant) || "standard".equals(variant);
        boolean runFix = "both".equals(variant) || "fix".equals(variant);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        System.out.println("=========================================================================");
        System.out.println(" BREAK THE CODE — EPISODE 3: CACHE LOAD BENCHMARK");
        System.out.printf(" Scenario: %s | Variant: %s | Duration: %ds | Cooldown: %ds%n",
                testType.toUpperCase(), variant.toUpperCase(), duration, cooldown);
        System.out.printf(" Concurrency: %d concurrent virtual threads%n", CONCURRENCY);
        System.out.println("=========================================================================");

        // Warm up JVM JIT, HTTP connection pools, and Redisson Netty connections
        System.out.println("[0] Warming up JVM, HTTP client, and Redisson connections...");
        warmup(client);
        System.out.println("[*] Warmup complete.\n");

        if ("all".equals(testType) || "stampede".equals(testType)) {
            System.out.println("=========================================================================");
            System.out.printf(" EXPERIMENT 1: CACHE STAMPEDE / THUNDERING HERD (%d Threads, %ds Duration)%n", CONCURRENCY, duration);
            System.out.printf(" Target Variant: %s%n", variant.toUpperCase());
            System.out.println("=========================================================================");

            if (runStandard) {
                inspectCacheState(client, "catalog::hot-deal", "INITIAL CACHE STATE");
                runStampedeBenchmark(client, "STANDARD CACHE-ASIDE (All 50 threads hit DB simultaneously)", "/hot-deal/standard", duration);
            }

            if (runStandard && runFix) {
                breathe(cooldown);
            }

            if (runFix) {
                runStampedeBenchmark(client, "DISTRIBUTED MUTEX + DCL (Only 1 thread hits DB; 49 spin-poll Redis)", "/hot-deal/mutex", duration);
                inspectCacheState(client, "catalog::hot-deal", "FINAL CACHE STATE AFTER MUTEX BENCHMARK");
            }
        }

        if ("all".equals(testType) || "penetration".equals(testType)) {
            System.out.println("\n=========================================================================");
            System.out.printf(" EXPERIMENT 2: CACHE PENETRATION (%d Concurrent Threads, %ds Duration)%n", CONCURRENCY, duration);
            System.out.printf(" Target Variant: %s%n", variant.toUpperCase());
            System.out.println("=========================================================================");

            if (runStandard) {
                runPenetrationBenchmark(client, "STANDARD PENETRATION (Null is never cached; 100% hit DB)", "/product/invalid-uuid-999/standard", false, duration);
            }

            if (runStandard && runFix) {
                breathe(cooldown);
            }

            if (runFix) {
                runPenetrationBenchmark(client, "OPTIMIZED SENTINEL (Sentinel cached; 100% hit Redis in <1ms)", "/product/invalid-uuid-999/sentinel", true, duration);
                inspectCacheState(client, "catalog::product::invalid-uuid-999", "SENTINEL CACHE STATE FOR NON-EXISTENT ID");
            }
        }

        if ("all".equals(testType) || "cascade".equals(testType)) {
            System.out.println("\n=========================================================================");
            System.out.println(" EXPERIMENT 3: INVALIDATION CASCADES (Midnight ERP Sync vs TTL Jitter)");
            System.out.printf(" Target Variant: %s%n", variant.toUpperCase());
            System.out.println("=========================================================================");

            if (runStandard) {
                runCascadeBenchmark(client, "STANDARD FIXED TTL (20 keys expire simultaneously at t=4s -> 100% Miss Spike)", "standard", 20);
            }

            if (runStandard && runFix) {
                breathe(cooldown);
            }

            if (runFix) {
                runCascadeBenchmark(client, "OPTIMIZED TTL JITTER (20 keys expire staggered across 4s-11s window)", "jitter", 20);
            }
        }

        // Print final Prometheus counters
        printPrometheusSummary(client);
    }

    private static String normalizeVariant(String raw) {
        if ("naive".equals(raw) || "standard".equals(raw)) {
            return "standard";
        }
        if ("fix".equals(raw) || "mutex".equals(raw) || "sentinel".equals(raw) || "jitter".equals(raw) || "optimized".equals(raw)) {
            return "fix";
        }
        return "both";
    }

    private static void breathe(int seconds) throws InterruptedException {
        System.out.println();
        System.out.printf("[*] Prometheus Breathing Window: Cooling down for %d seconds so 15s/30s scrape buffers settle to baseline...%n", seconds);
        for (int remaining = seconds; remaining > 0; remaining--) {
            System.out.printf("    [Prometheus Settling] %2d seconds remaining...\r", remaining);
            Thread.sleep(1000);
        }
        System.out.println("    [Prometheus Settling] Baseline cleared! Proceeding to the optimized fix run...   \n");
    }

    private static void warmup(HttpClient client) {
        try {
            HttpRequest req1 = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/hot-deal/standard")).GET().build();
            HttpRequest req2 = HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/hot-deal/mutex")).GET().build();
            client.send(req1, HttpResponse.BodyHandlers.discarding());
            client.send(req2, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    private static void inspectCacheState(HttpClient client, String key, String banner) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/inspect?key=" + key))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            System.out.println("\n-------------------------------------------------------------------------");
            System.out.printf(" [REDIS INSPECTION] %s: '%s'%n", banner, key);
            System.out.println(" Cache Payload: " + resp.body());
            System.out.println("-------------------------------------------------------------------------");
        } catch (Exception e) {
            System.out.printf(" [!] Failed to inspect cache key '%s': %s%n", key, e.getMessage());
        }
    }

    private static void runStampedeBenchmark(HttpClient client, String title, String path, int durationSeconds) throws Exception {
        System.out.println("\n>>> SCENARIO: " + title);
        System.out.println("Target: " + BASE_URL + path);
        System.out.printf("[*] Running sustained stampede for %d seconds across waves of %d concurrent threads...%n", durationSeconds, CONCURRENCY);

        inspectCacheState(client, "catalog::hot-deal", "BEFORE EVICTION");

        HttpRequest evictReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/evict?key=catalog::hot-deal"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        client.send(evictReq, HttpResponse.BodyHandlers.discarding());
        System.out.println("[*] Key 'catalog::hot-deal' evicted from Redis! Cache is now EMPTY.");
        inspectCacheState(client, "catalog::hot-deal", "AFTER EVICTION (COLD CACHE)");

        AtomicInteger totalSuccess = new AtomicInteger();
        AtomicInteger totalFailures = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();
        long deadline = startTime + (durationSeconds * 1000L);
        int waveCount = 0;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (System.currentTimeMillis() < deadline) {
                waveCount++;

                if (waveCount > 1) {
                    client.send(evictReq, HttpResponse.BodyHandlers.discarding());
                }

                CountDownLatch startGate = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < CONCURRENCY; i++) {
                    futures.add(executor.submit(() -> {
                        try {
                            startGate.await();
                            long reqStart = System.currentTimeMillis();
                            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(URI.create(BASE_URL + path))
                                    .timeout(Duration.ofMillis(5000))
                                    .GET().build();

                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            long elapsed = System.currentTimeMillis() - reqStart;
                            latencies.add(elapsed);

                            if (response.statusCode() == 200) {
                                totalSuccess.incrementAndGet();
                            } else {
                                totalFailures.incrementAndGet();
                            }
                        } catch (Exception e) {
                            totalFailures.incrementAndGet();
                        }
                    }));
                }

                startGate.countDown();

                for (var f : futures) {
                    try { f.get(); } catch (Exception ignored) {}
                }

                long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
                System.out.printf("  [%2ds / %2ds] Wave #%d (%d threads) -> Total Success: %d | Timeouts/Errors: %d%n",
                        elapsedSec, durationSeconds, waveCount, CONCURRENCY, totalSuccess.get(), totalFailures.get());

                Thread.sleep(100);
            }
        }

        long totalElapsed = System.currentTimeMillis() - startTime;
        printResults(title, totalElapsed, totalSuccess.get(), totalFailures.get(), latencies);

        inspectCacheState(client, "catalog::hot-deal", "RE-POPULATED CACHE AFTER BENCHMARK");
    }

    private static void runPenetrationBenchmark(HttpClient client, String title, String path, boolean primeSentinel, int durationSeconds) throws Exception {
        System.out.println("\n>>> SCENARIO: " + title);
        System.out.println("Target: " + BASE_URL + path);

        if (primeSentinel) {
            HttpRequest prime = HttpRequest.newBuilder().uri(URI.create(BASE_URL + path)).GET().build();
            HttpResponse<Void> primeResp = client.send(prime, HttpResponse.BodyHandlers.discarding());
            System.out.printf("[*] 1st probe executed (HTTP %d): Product not in DB -> Sentinel cached in Redis.%n", primeResp.statusCode());
            inspectCacheState(client, "catalog::product::invalid-uuid-999", "SENTINEL ACTIVATION");
            System.out.printf("[*] Now running sustained probes for %d seconds across %d concurrent threads...%n", durationSeconds, CONCURRENCY);
        } else {
            HttpRequest evict = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/evict?key=catalog::product::invalid-uuid-999"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            client.send(evict, HttpResponse.BodyHandlers.discarding());
            System.out.printf("[*] Firing sustained probes for %d seconds against un-cached non-existent ID...%n", durationSeconds);
        }

        AtomicInteger count404 = new AtomicInteger();
        AtomicInteger countFailures = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();
        long deadline = startTime + (durationSeconds * 1000L);
        int waveCount = 0;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (System.currentTimeMillis() < deadline) {
                waveCount++;
                CountDownLatch startGate = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < CONCURRENCY; i++) {
                    futures.add(executor.submit(() -> {
                        try {
                            startGate.await();
                            long reqStart = System.currentTimeMillis();
                            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(URI.create(BASE_URL + path))
                                    .timeout(Duration.ofMillis(3000))
                                    .GET().build();

                            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                            long elapsed = System.currentTimeMillis() - reqStart;
                            latencies.add(elapsed);

                            if (response.statusCode() == 404) {
                                count404.incrementAndGet();
                            } else {
                                countFailures.incrementAndGet();
                            }
                        } catch (Exception e) {
                            countFailures.incrementAndGet();
                        }
                    }));
                }

                startGate.countDown();

                for (var f : futures) {
                    try { f.get(); } catch (Exception ignored) {}
                }

                long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
                System.out.printf("  [%2ds / %2ds] Wave #%d (%d threads) -> Total 404: %d | Failures: %d%n",
                        elapsedSec, durationSeconds, waveCount, CONCURRENCY, count404.get(), countFailures.get());

                Thread.sleep(100);
            }
        }

        long totalElapsed = System.currentTimeMillis() - startTime;
        printResults(title, totalElapsed, count404.get(), countFailures.get(), latencies);
    }

    private static void runCascadeBenchmark(HttpClient client, String title, String strategy, int productCount) throws Exception {
        System.out.println("\n>>> SCENARIO: " + title);
        System.out.println("Endpoint: POST " + BASE_URL + "/sync-erp?strategy=" + strategy + "&count=" + productCount);

        // 1. Trigger ERP Batch Sync with the requested strategy (standard = fixed 4s TTL, jitter = 4s-11s TTL)
        HttpRequest syncReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/sync-erp?strategy=" + strategy + "&count=" + productCount))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> syncResp = client.send(syncReq, HttpResponse.BodyHandlers.ofString());
        System.out.printf("[*] ERP Catalog Sync finished. Synced %d products into Redis.%n", productCount);

        // 2. Wait for the fixed 4s TTL window to expire
        System.out.println("[*] Waiting 4,200ms for expiration window...");
        Thread.sleep(4200);

        // 3. Fire productCount concurrent virtual threads, each requesting a distinct product (prod-1 to prod-N)
        System.out.printf("[*] Launching %d concurrent requests for distinct products ('prod-1' through 'prod-%d')...%n",
                productCount, productCount);

        AtomicInteger hits = new AtomicInteger();
        AtomicInteger misses = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 1; i <= productCount; i++) {
                final String prodId = "prod-" + i;
                futures.add(executor.submit(() -> {
                    try {
                        startGate.await();
                        long reqStart = System.currentTimeMillis();
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(BASE_URL + "/product/" + prodId + "/standard"))
                                .timeout(Duration.ofMillis(4000))
                                .GET().build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        long elapsed = System.currentTimeMillis() - reqStart;
                        latencies.add(elapsed);

                        if (response.statusCode() == 200) {
                            if (elapsed < 20) {
                                hits.incrementAndGet();
                            } else {
                                misses.incrementAndGet();
                            }
                        } else {
                            failures.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                }));
            }

            startGate.countDown();
            for (var f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        }

        long totalElapsed = System.currentTimeMillis() - startTime;
        Collections.sort(latencies);
        long min = latencies.isEmpty() ? 0 : latencies.get(0);
        long max = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        long p50 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.50));
        long p99 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.99));

        System.out.printf("--- SUMMARY for [%s] ---%n", title);
        System.out.printf(" Total Wave Duration: %d ms%n", totalElapsed);
        System.out.printf(" Cache Hits (from Redis in <20ms): %d / %d%n", hits.get(), productCount);
        System.out.printf(" Cache Misses (Database Queries triggered): %d / %d%n", misses.get(), productCount);
        System.out.printf(" Failures / Timeouts: %d%n", failures.get());
        System.out.printf(" Latency (min/p50/p99/max): %d / %d / %d / %d ms%n", min, p50, p99, max);
    }

    private static void printResults(String title, long totalElapsed, int success, int failures, List<Long> latencies) {
        Collections.sort(latencies);
        long min = latencies.isEmpty() ? 0 : latencies.get(0);
        long max = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        long p50 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.50));
        long p95 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.95));
        long p99 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.99));

        System.out.printf("--- SUMMARY for [%s] ---%n", title);
        System.out.printf(" Total Duration: %d ms%n", totalElapsed);
        System.out.printf(" Successful: %d%n", success);
        if (failures > 0) {
            System.out.printf(" [!] CONNECTION TIMEOUT / 500 ERROR: %d requests failed!%n", failures);
        } else {
            System.out.printf(" Failures / Timeouts: 0 (100%% Healthy)%n");
        }
        System.out.printf(" Latency (min/p50/p95/p99/max): %d / %d / %d / %d ms%n", min, p50, p95, p99, max);
    }

    private static void printPrometheusSummary(HttpClient client) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/actuator/prometheus"))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            System.out.println("\n=========================================================================");
            System.out.println(" PROMETHEUS METRIC COUNTERS (Application State)");
            System.out.println("=========================================================================");
            for (String line : resp.body().split("\n")) {
                if ((line.startsWith("cache_gets_total") || line.startsWith("hikaricp_connections_timeout_total")) && !line.startsWith("#")) {
                    System.out.println(" " + line);
                }
            }
            System.out.println("=========================================================================");
        } catch (Exception ignored) {}
    }
}
