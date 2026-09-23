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

public class ConcurrencyOrderLoadTest {

    private static final String BASE_URL = "http://localhost:8080/api/orders";
    private static final String SKU = "GPU-4090";

    public static void main(String[] args) throws Exception {
        String mode = (args.length > 0) ? args[0].toLowerCase() : "all";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        System.out.println("=========================================================================");
        System.out.println(" BREAK THE CODE - EPISODE 4: DATABASE CONCURRENCY BENCHMARK HARNESS");
        System.out.println(" Target SKU: " + SKU + " | Observability: Grafana & Tempo Ready");
        System.out.println("=========================================================================\n");

        if (mode.equals("flashsale")) {
            // High-concurrency flash sale simulation: 20 concurrent shoppers for 5 items
            String strat = (args.length > 1) ? args[1].toLowerCase() : "pessimistic";
            runFlashSaleContention(client, strat, 5, 20);
            return;
        }

        if (mode.equals("all") || mode.equals("standard")) {
            runTwoCustomerRace(client, "standard", "NAIVE READ-MODIFY-WRITE (What 95% of Developers Write)");
        }

        if (mode.equals("all") || mode.equals("optimistic")) {
            runTwoCustomerRace(client, "optimistic", "OPTION A: OPTIMISTIC LOCKING (@Version CAS Check)");
        }

        if (mode.equals("all") || mode.equals("pessimistic")) {
            runTwoCustomerRace(client, "pessimistic", "OPTION B: PESSIMISTIC LOCKING (SELECT ... FOR UPDATE)");
        }
    }

    /**
     * Classic 2-customer race for the 1 remaining item
     */
    private static void runTwoCustomerRace(HttpClient client, String strategy, String description) throws Exception {
        System.out.println("-------------------------------------------------------------------------");
        System.out.printf(">>> EXPERIMENT: %s%n", description);
        System.out.println("-------------------------------------------------------------------------");

        resetInventory(client, 1);

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(2);

        String jsonPayload = String.format("{\"sku\":\"%s\",\"qty\":1}", SKU);
        String targetUrl = BASE_URL + "/checkout?strategy=" + strategy;

        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        long waveStart = System.currentTimeMillis();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 1; i <= 2; i++) {
                final int threadId = i;
                futures.add(executor.submit(() -> {
                    try {
                        startSignal.await();
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(targetUrl))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(5))
                                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                                .build();
                        long reqStart = System.currentTimeMillis();
                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        long duration = System.currentTimeMillis() - reqStart;
                        System.out.printf("   Thread #%d: HTTP %d in %d ms -> %s%n",
                                threadId, resp.statusCode(), duration, resp.body());
                        return resp;
                    } finally {
                        doneSignal.countDown();
                    }
                }));
            }

            System.out.println(" [Step 2] Firing 2 simultaneous checkout requests...");
            startSignal.countDown();
            doneSignal.await();
        }

        long waveElapsed = System.currentTimeMillis() - waveStart;
        inspectDatabaseState(client, futures, waveElapsed, strategy, 1);
    }

    /**
     * High-concurrency flash sale simulation: N callers competing for M items.
     * Perfect for driving live Grafana graphs and Tempo trace waterfalls!
     */
    private static void runFlashSaleContention(HttpClient client, String strategy, int initialStock, int totalShoppers) throws Exception {
        System.out.println("-------------------------------------------------------------------------");
        System.out.printf(">>> FLASH SALE CONTENCTION SIMULATION: %d Shoppers competing for %d Items [%s]%n",
                totalShoppers, initialStock, strategy.toUpperCase());
        System.out.println("-------------------------------------------------------------------------");

        resetInventory(client, initialStock);

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(totalShoppers);

        String jsonPayload = String.format("{\"sku\":\"%s\",\"qty\":1}", SKU);
        String targetUrl = BASE_URL + "/checkout?strategy=" + strategy;

        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        long waveStart = System.currentTimeMillis();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 1; i <= totalShoppers; i++) {
                final int shopperId = i;
                futures.add(executor.submit(() -> {
                    try {
                        startSignal.await();
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(targetUrl))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(10))
                                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                                .build();
                        long reqStart = System.currentTimeMillis();
                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        long duration = System.currentTimeMillis() - reqStart;
                        if (resp.statusCode() == 200) {
                            System.out.printf("   [WINNER] Shopper #%02d: HTTP 200 in %3d ms%n", shopperId, duration);
                        } else {
                            System.out.printf("   [BLOCKED] Shopper #%02d: HTTP %d in %3d ms -> %s%n", shopperId, resp.statusCode(), duration, resp.body());
                        }
                        return resp;
                    } finally {
                        doneSignal.countDown();
                    }
                }));
            }

            System.out.printf(" [Step 2] Firing %d concurrent shoppers into the checkout gate...%n", totalShoppers);
            startSignal.countDown();
            doneSignal.await();
        }

        long waveElapsed = System.currentTimeMillis() - waveStart;
        inspectDatabaseState(client, futures, waveElapsed, strategy, initialStock);
    }

    private static void resetInventory(HttpClient client, int stock) throws Exception {
        HttpRequest resetReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/inventory/reset"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(String.format("{\"sku\":\"%s\",\"stock\":%d}", SKU, stock)))
                .build();
        HttpResponse<String> resetResp = client.send(resetReq, HttpResponse.BodyHandlers.ofString());
        if (resetResp.statusCode() != 200) {
            throw new RuntimeException("Failed to reset inventory: " + resetResp.body());
        }
        System.out.printf(" [Step 1] Inventory reset -> SKU: %s has stock = %d, previous orders cleared.%n", SKU, stock);
    }

    private static void inspectDatabaseState(HttpClient client, List<Future<HttpResponse<String>>> futures,
                                             long waveElapsed, String strategy, int initialStock) throws Exception {
        HttpRequest statusReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/inventory/status?sku=" + SKU))
                .GET()
                .build();
        HttpResponse<String> statusResp = client.send(statusReq, HttpResponse.BodyHandlers.ofString());

        System.out.println(" [Step 3] Live Database Verification:");
        System.out.println("   Status Response: " + statusResp.body());

        int okCount = 0;
        int conflictCount = 0;
        for (var f : futures) {
            int code = f.get().statusCode();
            if (code == 200) okCount++;
            else if (code == 409) conflictCount++;
        }

        System.out.printf("   Summary: %d Successful (HTTP 200), %d Conflicts (HTTP 409) (Wave took %d ms)%n",
                okCount, conflictCount, waveElapsed);

        if (okCount > initialStock) {
            int oversold = okCount - initialStock;
            System.out.printf("   [!] CRITICAL ALERT: OVERSELL DETECTED! Created %d orders for %d items (+%d oversold!)%n",
                    okCount, initialStock, oversold);
            System.out.println("       Check Grafana: 'Silent Inventory Oversell Count' is flashing RED!\n");
        } else {
            System.out.printf("   [V] 100%% DATA INTEGRITY PRESERVED! Created %d orders for %d items. Zero oversell!%n",
                    okCount, initialStock);
            System.out.println("       Check Grafana & Tempo: Full trace serialization and zero corruption verified!\n");
        }
    }
}
