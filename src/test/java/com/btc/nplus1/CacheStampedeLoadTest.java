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
 * Break The Code — Episode 3: Cache Stampede & Penetration Load Generator.
 * Dispatches 50 concurrent virtual threads at the exact same millisecond against
 * naive vs. mutex-protected endpoints after evicting the cache entry.
 */
public class CacheStampedeLoadTest {

    private static final String BASE_URL = "http://localhost:8080/api/catalog";
    private static final int CONCURRENCY = 50;

    public static void main(String[] args) throws Exception {
        String testType = args.length > 0 ? args[0].toLowerCase() : "all";

        System.out.println("=========================================================================");
        System.out.println(" BREAK THE CODE — EPISODE 3: CACHE LOAD BENCHMARK");
        System.out.printf(" Concurrency: %d concurrent virtual threads%n", CONCURRENCY);
        System.out.println("=========================================================================");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        if ("all".equals(testType) || "stampede".equals(testType)) {
            runStampedeBenchmark(client, "NAIVE CACHE-ASIDE (Vulnerable)", "/hot-deal/naive");
            Thread.sleep(2000);
            runStampedeBenchmark(client, "DISTRIBUTED MUTEX + DOUBLE-CHECK (Optimized)", "/hot-deal/mutex");
        }

        if ("all".equals(testType) || "penetration".equals(testType)) {
            System.out.println("\n-------------------------------------------------------------------------");
            runPenetrationBenchmark(client, "NAIVE PENETRATION (No Sentinel)", "/product/invalid-uuid-999/naive");
            Thread.sleep(2000);
            runPenetrationBenchmark(client, "OPTIMIZED SENTINEL (Empty Placeholder)", "/product/invalid-uuid-999/sentinel");
        }
    }

    private static void runStampedeBenchmark(HttpClient client, String title, String path) throws Exception {
        System.out.println("\n>>> TEST: " + title);
        System.out.println("Target: " + BASE_URL + path);

        // 1. Warm the cache first
        HttpRequest warmReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .GET().build();
        client.send(warmReq, HttpResponse.BodyHandlers.discarding());
        System.out.println("[*] Primed cache entry: HTTP 200 received.");

        // 2. Invalidate cache key to trigger the stampede scenario
        HttpRequest evictReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/evict?key=catalog::hot-deal"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        client.send(evictReq, HttpResponse.BodyHandlers.discarding());
        System.out.println("[*] Evicted key 'catalog::hot-deal'. Simulating simultaneous expiry/purge...");

        // 3. Fire CONCURRENCY (50) concurrent virtual threads
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();
        CountDownLatch startGate = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        startGate.await(); // Synchronize all virtual threads to launch at the exact same instant
                        long reqStart = System.currentTimeMillis();
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(BASE_URL + path))
                                .timeout(Duration.ofMillis(3000))
                                .GET().build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        long elapsed = System.currentTimeMillis() - reqStart;
                        latencies.add(elapsed);

                        if (response.statusCode() == 200) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }));
            }

            // Unleash all 50 virtual threads simultaneously
            startGate.countDown();

            for (var f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        }

        long totalElapsed = System.currentTimeMillis() - startTime;
        printResults(title, totalElapsed, successCount.get(), failureCount.get(), latencies);
    }

    private static void runPenetrationBenchmark(HttpClient client, String title, String path) throws Exception {
        System.out.println("\n>>> TEST: " + title);
        System.out.println("Target: " + BASE_URL + path);

        AtomicInteger success404 = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();
        CountDownLatch startGate = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
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
                            success404.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }));
            }

            startGate.countDown();

            for (var f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        }

        long totalElapsed = System.currentTimeMillis() - startTime;
        printResults(title, totalElapsed, success404.get(), failureCount.get(), latencies);
    }

    private static void printResults(String title, long totalElapsed, int success, int failures, List<Long> latencies) {
        Collections.sort(latencies);
        long min = latencies.isEmpty() ? 0 : latencies.get(0);
        long max = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        long p50 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.50));
        long p95 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.95));
        long p99 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.99));

        System.out.printf("--- SUMMARY for [%s] ---%n", title);
        System.out.printf(" Total Wave Duration: %d ms%n", totalElapsed);
        System.out.printf(" Successful Responses: %d / %d%n", success, CONCURRENCY);
        System.out.printf(" Starved / Timed Out:  %d%n", failures);
        System.out.printf(" Latency (min/p50/p95/p99/max): %d / %d / %d / %d / %d ms%n", min, p50, p95, p99, max);
    }
}
