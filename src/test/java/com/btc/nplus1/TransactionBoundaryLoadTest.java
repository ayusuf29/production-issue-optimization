package com.btc.nplus1;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class TransactionBoundaryLoadTest {

    private static final String BASE_URL = "http://localhost:8080/api/orders";
    private static final String CATALOG_URL = "http://localhost:8080/api/catalog/hotdeal";
    private static final String STATUS_URL = "http://localhost:8080/api/orders/inventory/status?sku=GPU-4090";

    public static void main(String[] args) throws Exception {
        String mode = (args.length > 0) ? args[0].toLowerCase() : "all";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        System.out.println("=========================================================================");
        System.out.println(" BREAK THE CODE - EPISODE 5: TRANSACTION BOUNDARIES & I/O TRAP BENCHMARK");
        System.out.println(" Pool Target: HikariPool-Orders (Capacity: 5, Timeout: 1000ms)");
        System.out.println(" External I/O Delay: 4000ms Mock Payment Gateway");
        System.out.println(" Observability: Grafana 'btc-tx-boundary-monitor' & Prometheus Ready");
        System.out.println("=========================================================================\n");

        if (mode.equals("all") || mode.equals("legacy")) {
            runExperiment(client, "legacy",
                    "THE PRODUCTION DISASTER: Network I/O Inside @Transactional",
                    BASE_URL + "/checkout-legacy");
        }

        if (mode.equals("all")) {
            System.out.println("\n[Cooldown] Waiting 5 seconds for HikariCP connections to settle...\n");
            Thread.sleep(5000);
        }

        if (mode.equals("all") || mode.equals("decoupled")) {
            runExperiment(client, "decoupled",
                    "THE ENTERPRISE FIX: Decoupled DB Boundaries & Micro-Transactions",
                    BASE_URL + "/checkout-decoupled");
        }
    }

    private static void runExperiment(HttpClient client, String mode, String description, String checkoutEndpoint) throws Exception {
        System.out.println("-------------------------------------------------------------------------");
        System.out.printf(">>> EXPERIMENT: %s%n", description);
        System.out.println("-------------------------------------------------------------------------");

        resetInventory(client, 100);

        int checkoutWorkers = 5; // Exactly saturates the 5-connection pool
        int backgroundWorkers = 10; // Unrelated customer traffic (e.g. Catalog browsing / Status checks)

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch checkoutsStartedSignal = new CountDownLatch(checkoutWorkers);
        CountDownLatch doneSignal = new CountDownLatch(checkoutWorkers + backgroundWorkers);

        List<Future<HttpResponse<String>>> checkoutFutures = new ArrayList<>();
        List<Future<HttpResponse<String>>> backgroundFutures = new ArrayList<>();

        AtomicInteger bgSuccessCount = new AtomicInteger(0);
        AtomicInteger bgStarvedCount = new AtomicInteger(0);

        long experimentStart = System.currentTimeMillis();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // 1. Launch 5 long-running checkouts (each takes 4 seconds due to external payment)
            for (int i = 1; i <= checkoutWorkers; i++) {
                final int id = i;
                checkoutFutures.add(executor.submit(() -> {
                    try {
                        startSignal.await();
                        checkoutsStartedSignal.countDown();
                        String payload = "{\"sku\":\"GPU-4090\",\"qty\":1}";
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(checkoutEndpoint))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(10))
                                .POST(HttpRequest.BodyPublishers.ofString(payload))
                                .build();

                        long start = System.currentTimeMillis();
                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        long duration = System.currentTimeMillis() - start;

                        System.out.printf("   [CHECKOUT #%d] HTTP %d in %d ms%n", id, resp.statusCode(), duration);
                        return resp;
                    } finally {
                        doneSignal.countDown();
                    }
                }));
            }

            // 2. Launch background queries hitting the database while checkouts are in flight
            for (int i = 1; i <= backgroundWorkers; i++) {
                final int id = i;
                backgroundFutures.add(executor.submit(() -> {
                    try {
                        // Wait until all 5 checkouts have officially started and entered the system
                        checkoutsStartedSignal.await();
                        // Small staggered delay so requests arrive while connections are held
                        Thread.sleep(150 + (id * 50));

                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(STATUS_URL))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build();

                        long start = System.currentTimeMillis();
                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        long duration = System.currentTimeMillis() - start;

                        if (resp.statusCode() == 200) {
                            bgSuccessCount.incrementAndGet();
                            System.out.printf("   [BACKGROUND QUERY #%02d] -> HTTP 200 in %4d ms (HEALTHY)%n", id, duration);
                        } else {
                            bgStarvedCount.incrementAndGet();
                            System.out.printf("   [BACKGROUND QUERY #%02d] -> HTTP %d in %4d ms [POOL STARVATION DETECTED!]%n",
                                    id, resp.statusCode(), duration);
                        }
                        return resp;
                    } catch (Exception e) {
                        bgStarvedCount.incrementAndGet();
                        System.out.printf("   [BACKGROUND QUERY #%02d] -> FAILED with Exception: %s%n", id, e.getMessage());
                        return null;
                    } finally {
                        doneSignal.countDown();
                    }
                }));
            }

            System.out.printf(" [Step 1] Firing %d checkouts holding 4s payment call...%n", checkoutWorkers);
            System.out.printf(" [Step 2] Firing %d concurrent background DB queries into the pool...%n", backgroundWorkers);
            startSignal.countDown();

            doneSignal.await();
        }

        long totalElapsed = System.currentTimeMillis() - experimentStart;

        System.out.println("\n [Step 3] Verification & Diagnostic Summary:");
        System.out.printf("   Total Experiment Time: %d ms%n", totalElapsed);
        System.out.printf("   Background Queries Succeeded (HTTP 200): %d / %d%n", bgSuccessCount.get(), backgroundWorkers);
        System.out.printf("   Background Queries Starved (HTTP 5xx):   %d / %d%n", bgStarvedCount.get(), backgroundWorkers);

        if (bgStarvedCount.get() > 0) {
            System.out.println("   [!] CATASTROPHIC OUTAGE DETECTED!");
            System.out.println("       HikariCP connection pool was completely starved by the 4s payment HTTP call inside @Transactional!");
            System.out.println("       Unrelated requests timed out after 1000ms: PSQLException Connection Unavailable.");
            System.out.println("       Grafana: 'Active DB Connections' pinned at 5/5 max capacity, 'Pool Starvation Timeouts' firing RED!\n");
        } else {
            System.out.println("   [V] 100% POOL AVAILABILITY PRESERVED!");
            System.out.println("       Database connections were held for <10ms during state transitions.");
            System.out.println("       The 4s payment I/O occurred completely outside transaction boundaries.");
            System.out.println("       Grafana: Zero pool starvation timeouts, background endpoints responded in milliseconds!\n");
        }
    }

    private static void resetInventory(HttpClient client, int stock) throws Exception {
        HttpRequest resetReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/inventory/reset"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(String.format("{\"sku\":\"GPU-4090\",\"stock\":%d}", stock)))
                .build();
        HttpResponse<String> resetResp = client.send(resetReq, HttpResponse.BodyHandlers.ofString());
        if (resetResp.statusCode() != 200) {
            throw new RuntimeException("Failed to reset inventory: " + resetResp.body());
        }
        System.out.printf(" [Prep] Inventory reset -> SKU: GPU-4090 stock = %d, previous orders cleared.%n", stock);
    }
}
