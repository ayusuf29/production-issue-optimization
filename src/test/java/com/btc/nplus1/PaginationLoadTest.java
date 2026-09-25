package com.btc.nplus1;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load Test Harness for Episode 2: Offset vs Keyset vs Deferred Join Pagination.
 *
 * Usage:
 *   ./mvnw.cmd test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass="com.btc.nplus1.PaginationLoadTest" -Dexec.args="offset"
 *   ./mvnw.cmd test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass="com.btc.nplus1.PaginationLoadTest" -Dexec.args="deferred"
 *   ./mvnw.cmd test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass="com.btc.nplus1.PaginationLoadTest" -Dexec.args="keyset"
 *
 * System Properties (optional):
 *   -Dstrategy=offset|deferred|keyset
 *   -Dduration=30
 *   -Dconcurrency=25
 *   -Dlimit=20
 *   -DbaseUrl=http://localhost:8080
 */
public class PaginationLoadTest {

    private static final Pattern CURSOR_PATTERN =
            Pattern.compile("\"nextCursor\"\\s*:\\s*(?:\"([^\"]+)\"|null)");
    private static final Pattern HAS_MORE_PATTERN =
            Pattern.compile("\"hasMore\"\\s*:\\s*(true|false)");

    public static void main(String[] args) throws Exception {
        String strategy = args.length > 0
                ? args[0].trim().toLowerCase()
                : System.getProperty("strategy", "offset").trim().toLowerCase();

        if (!List.of("offset", "deferred", "keyset").contains(strategy)) {
            System.err.printf("Invalid strategy '%s'. Supported strategies: offset, deferred, keyset%n", strategy);
            System.exit(1);
        }

        int concurrency = Integer.parseInt(System.getProperty("concurrency", "25"));
        int durationSec = Integer.parseInt(System.getProperty("duration", "30"));
        int limit = Integer.parseInt(System.getProperty("limit", "20"));
        String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
        Duration duration = Duration.ofSeconds(durationSec);

        // Maximum offset wrap-around to prevent requesting beyond the 100k seeded records
        final int maxOffset = 90_000;

        System.out.println("=========================================================================");
        System.out.println("        Break The Code — Episode 2: Pagination Benchmark Harness         ");
        System.out.println("=========================================================================");
        System.out.printf(" Strategy      : %s%n", strategy.toUpperCase());
        System.out.printf(" Base URL      : %s%n", baseUrl);
        System.out.printf(" Concurrency   : %d virtual threads per wave%n", concurrency);
        System.out.printf(" Page Size     : %d items%n", limit);
        System.out.printf(" Duration      : %d seconds%n", durationSec);
        if ("keyset".equals(strategy)) {
            System.out.println(" Mode          : Dynamic cursor progression across concurrency loop");
        } else {
            System.out.printf(" Mode          : Next offset (+%d) per request (non-blocking)%n", limit);
        }
        System.out.println("=========================================================================");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        AtomicInteger totalSuccess = new AtomicInteger();
        AtomicInteger totalFailed = new AtomicInteger();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        // Shared non-blocking offset counter for offset/deferred strategies
        AtomicInteger currentOffset = new AtomicInteger(0);

        // Shared cursor reference for keyset strategy across concurrency loop
        AtomicReference<String> currentCursor = new AtomicReference<>(null);

        Instant startTime = Instant.now();
        Instant deadline = startTime.plus(duration);
        int waveNumber = 0;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (Instant.now().isBefore(deadline)) {
                waveNumber++;
                long waveStart = System.currentTimeMillis();
                List<Future<Boolean>> futures = new ArrayList<>(concurrency);

                // Dispatch a wave of CONCURRENCY requests using Virtual Threads
                for (int i = 0; i < concurrency; i++) {
                    futures.add(executor.submit(() -> {
                        if (Instant.now().isAfter(deadline)) {
                            return false;
                        }

                        String requestUrl;
                        if ("keyset".equals(strategy)) {
                            // In Keyset, pick up the latest cursor in the loop of concurrency
                            String cursor = currentCursor.get();
                            if (cursor == null || cursor.isBlank()) {
                                requestUrl = String.format("%s/api/orders/keyset?size=%d", baseUrl, limit);
                            } else {
                                requestUrl = String.format("%s/api/orders/keyset?cursor=%s&size=%d",
                                        baseUrl, URLEncoder.encode(cursor, StandardCharsets.UTF_8), limit);
                            }
                        } else {
                            // In Offset/Deferred: next offset by plus limit on each request (non-blocking)
                            int offset = currentOffset.getAndAdd(limit);
                            if (offset > maxOffset) {
                                currentOffset.set(0);
                                offset = 0;
                            }
                            requestUrl = String.format("%s/api/orders/offset?strategy=%s&offset=%d&size=%d",
                                    baseUrl, strategy, offset, limit);
                        }

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(requestUrl))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build();

                        long reqStart = System.currentTimeMillis();
                        try {
                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            long reqElapsed = System.currentTimeMillis() - reqStart;
                            latencies.add(reqElapsed);

                            if (response.statusCode() == 200) {
                                totalSuccess.incrementAndGet();

                                // For keyset, update next cursor for subsequent requests in the loop
                                if ("keyset".equals(strategy)) {
                                    String body = response.body();
                                    String nextCursor = extractCursor(body);
                                    boolean hasMore = extractHasMore(body);

                                    if (nextCursor != null && !nextCursor.isBlank() && hasMore) {
                                        currentCursor.set(nextCursor);
                                    } else {
                                        // Reset to beginning when reaching end of data
                                        currentCursor.set(null);
                                    }
                                }
                                return true;
                            } else {
                                totalFailed.incrementAndGet();
                                return false;
                            }
                        } catch (Exception e) {
                            totalFailed.incrementAndGet();
                            return false;
                        }
                    }));
                }

                // Wait for all requests in this wave to complete
                int waveSuccess = 0;
                int waveFailed = 0;
                for (var future : futures) {
                    try {
                        if (Boolean.TRUE.equals(future.get())) {
                            waveSuccess++;
                        } else {
                            waveFailed++;
                        }
                    } catch (ExecutionException | InterruptedException e) {
                        waveFailed++;
                    }
                }

                long waveElapsed = System.currentTimeMillis() - waveStart;
                long totalElapsedSec = Duration.between(startTime, Instant.now()).toSeconds();

                String stateInfo = "keyset".equals(strategy)
                        ? "Cursor: " + truncate(currentCursor.get(), 18)
                        : "Offset: " + currentOffset.get();

                System.out.printf("[%2ds / %2ds] Wave #%-3d sent %2d reqs -> %2d OK, %2d Failed (%4d ms) | %s | Cumulative: %d OK%n",
                        totalElapsedSec, durationSec,
                        waveNumber, futures.size(),
                        waveSuccess, waveFailed,
                        waveElapsed,
                        stateInfo,
                        totalSuccess.get());
            }
        }

        long totalTimeMs = Duration.between(startTime, Instant.now()).toMillis();
        double throughput = (totalSuccess.get() * 1000.0) / Math.max(totalTimeMs, 1);

        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);

        long min = sortedLatencies.isEmpty() ? 0 : sortedLatencies.get(0);
        long p50 = percentile(sortedLatencies, 0.50);
        long p95 = percentile(sortedLatencies, 0.95);
        long p99 = percentile(sortedLatencies, 0.99);
        long max = sortedLatencies.isEmpty() ? 0 : sortedLatencies.get(sortedLatencies.size() - 1);

        System.out.println("=========================================================================");
        System.out.printf(" Benchmark Results: %s Strategy%n", strategy.toUpperCase());
        System.out.println("=========================================================================");
        System.out.printf(" Total Duration        : %.2f seconds%n", totalTimeMs / 1000.0);
        System.out.printf(" Total Successful Reqs : %d%n", totalSuccess.get());
        System.out.printf(" Total Failed Reqs     : %d%n", totalFailed.get());
        System.out.printf(" Throughput            : %.2f reqs/sec%n", throughput);
        System.out.printf(" Latency Min / Max     : %d ms / %d ms%n", min, max);
        System.out.printf(" Latency p50 (Median)  : %d ms%n", p50);
        System.out.printf(" Latency p95           : %d ms%n", p95);
        System.out.printf(" Latency p99           : %d ms%n", p99);
        System.out.println("=========================================================================");
    }

    private static String extractCursor(String json) {
        if (json == null) return null;
        Matcher m = CURSOR_PATTERN.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static boolean extractHasMore(String json) {
        if (json == null) return false;
        Matcher m = HAS_MORE_PATTERN.matcher(json);
        if (m.find()) {
            return "true".equalsIgnoreCase(m.group(1));
        }
        return false;
    }

    private static long percentile(List<Long> latencies, double percentile) {
        if (latencies.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * latencies.size()) - 1;
        return latencies.get(Math.max(0, Math.min(index, latencies.size() - 1)));
    }

    private static String truncate(String val, int maxLen) {
        if (val == null) return "none";
        return val.length() <= maxLen ? val : val.substring(0, maxLen) + "...";
    }
}
