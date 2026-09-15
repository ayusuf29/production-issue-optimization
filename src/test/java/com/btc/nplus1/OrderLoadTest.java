package com.btc.nplus1;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderLoadTest {

    private static final String TARGET_URL = "http://localhost:8080/api/orders?strategy=entitygraph&limit=50";
    private static final int CONCURRENCY = 25;
    private static final Duration DURATION = Duration.ofSeconds(30);

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=========================================================================");
        System.out.printf(" Starting Load Test against: %s%n", TARGET_URL);
        System.out.printf(" Concurrency: %d workers | Duration: %d seconds%n", CONCURRENCY, DURATION.toSeconds());
        System.out.println("=========================================================================");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        AtomicInteger totalSuccess = new AtomicInteger();
        AtomicInteger totalFailed = new AtomicInteger();
        Instant deadline = Instant.now().plus(DURATION);
        Instant startTime = Instant.now();

        int waveNumber = 0;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (Instant.now().isBefore(deadline)) {
                waveNumber++;
                long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
                if (remainingMs <= 0) break;

                long waveStart = System.currentTimeMillis();
                List<Future<Boolean>> futures = new ArrayList<>();

                // Dispatch a wave of CONCURRENCY (25) requests in parallel
                for (int i = 0; i < CONCURRENCY; i++) {
                    futures.add(executor.submit(() -> {
                        if (Instant.now().isAfter(deadline)) {
                            return false; // Time limit reached
                        }
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(TARGET_URL))
                                .timeout(Duration.ofSeconds(4))
                                .GET()
                                .build();
                        try {
                            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                            if (response.statusCode() == 200) {
                                totalSuccess.incrementAndGet();
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

                // Wait for all 25 requests in this wave to finish
                int waveSuccess = 0;
                int waveFailed = 0;
                for (var future : futures) {
                    try {
                        Boolean ok = future.get();
                        if (Boolean.TRUE.equals(ok)) waveSuccess++;
                        else waveFailed++;
                    } catch (ExecutionException e) {
                        waveFailed++;
                    }
                }

                long waveElapsed = System.currentTimeMillis() - waveStart;
                long totalElapsedSec = Duration.between(startTime, Instant.now()).toSeconds();

                System.out.printf("[%2ds / %2ds] Wave #%d sent %d requests -> %d OK, %d Failed (took %d ms) | Cumulative: %d OK, %d Failed%n",
                        totalElapsedSec, DURATION.toSeconds(),
                        waveNumber, futures.size(),
                        waveSuccess, waveFailed,
                        waveElapsed,
                        totalSuccess.get(), totalFailed.get());
            }
        }

        long totalTime = Duration.between(startTime, Instant.now()).toMillis();
        double throughput = (totalSuccess.get() * 1000.0) / totalTime;

        System.out.println("=========================================================================");
        System.out.printf(" Load Test Finished in %.2f seconds!%n", totalTime / 1000.0);
        System.out.printf(" Total Completed: %d reqs%n", totalSuccess.get());
        System.out.printf(" Total Failed/Timeout: %d reqs%n", totalFailed.get());
        System.out.printf(" Throughput: %.2f reqs/sec%n", throughput);
        System.out.println("=========================================================================");
    }
}
