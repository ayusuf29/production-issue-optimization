package com.btc.nplus1;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderLoadTest {

    private static final String TARGET_URL = "http://localhost:8080/api/orders?strategy=entitygraph&limit=50";
    private static final int CONCURRENCY = 25;
    private static final Duration DURATION = Duration.ofSeconds(30);

    public static void main(String[] args) throws InterruptedException {
        System.out.printf("Starting load test against %s with %d virtual threads for %ds...%n",
                TARGET_URL, CONCURRENCY, DURATION.toSeconds());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        AtomicInteger totalRequests = new AtomicInteger();
        AtomicInteger failedRequests = new AtomicInteger();
        Instant deadline = Instant.now().plus(DURATION);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENCY; i++) {
                executor.submit(() -> {
                    while (Instant.now().isBefore(deadline)) {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(TARGET_URL))
                                .GET()
                                .build();
                        try {
                            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                            if (response.statusCode() == 200) {
                                totalRequests.incrementAndGet();
                            } else {
                                failedRequests.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failedRequests.incrementAndGet();
                        }
                    }
                });
            }
        } // Awaits virtual threads completion

        System.out.printf("Finished! Completed: %d reqs | Failed/Timed out: %d reqs%n",
                totalRequests.get(), failedRequests.get());
    }
}
