package com.btc.nplus1.controller;

import com.btc.nplus1.domain.InventoryItem;
import com.btc.nplus1.dto.*;
import com.btc.nplus1.exception.InsufficientStockException;
import com.btc.nplus1.repository.InventoryRepository;
import com.btc.nplus1.repository.OrderRepository;
import com.btc.nplus1.service.CheckoutService;
import com.btc.nplus1.service.OrderBatchService;
import com.btc.nplus1.service.OrderPlacementService;
import com.btc.nplus1.service.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final OrderService orderService;
    private final CheckoutService checkoutService;
    private final OrderPlacementService orderPlacementService;
    private final OrderBatchService orderBatchService;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final MeterRegistry meterRegistry;

    public OrderController(OrderService orderService,
                           CheckoutService checkoutService,
                           OrderPlacementService orderPlacementService,
                           OrderBatchService orderBatchService,
                           InventoryRepository inventoryRepository,
                           OrderRepository orderRepository,
                           MeterRegistry meterRegistry) {
        this.orderService = orderService;
        this.checkoutService = checkoutService;
        this.orderPlacementService = orderPlacementService;
        this.orderBatchService = orderBatchService;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Endpoint to demonstrate N+1 vs Optimized strategies side-by-side.
     *
     * Examples:
     * - GET /api/orders?strategy=nplus1&limit=50   (Produces 51 SQL queries)
     * - GET /api/orders?strategy=joinfetch        (Produces exactly 1 SQL query)
     * - GET /api/orders?strategy=entitygraph      (Produces exactly 1 SQL query)
     */
    @GetMapping
    public ResponseEntity<List<CustomerOrderResponse>> getOrders(
            @RequestParam(defaultValue = "nplus1") String strategy,
            @RequestParam(defaultValue = "50") int limit) {

        List<CustomerOrderResponse> response = orderService.getOrders(strategy, limit);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public ResponseEntity<List<OrderSummaryResponse>> getOrderSummaries() {
        log.info("Received getOrderSummaries request");
        return ResponseEntity.ok(orderService.getSummary());
    }

    @GetMapping("/offset")
    public ResponseEntity<Page<OrderSummaryResponse>> getOrdersOffset(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getOrdersOffset(page, size));
    }

    @GetMapping("/keyset")
    public ResponseEntity<CursorResponse<OrderSummaryResponse>> getOrdersKeyset(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getOrdersKeyset(cursor, size));
    }

    @GetMapping("/deferred")
    public ResponseEntity<List<OrderSummaryResponse>> getOrdersDeferred(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getOrdersDeferred(page, size));
    }

    // =========================================================================
    // TOPIC 4: CONCURRENCY, RACE CONDITIONS & LOST UPDATES
    // =========================================================================

    /**
     * Checkout endpoint supporting:
     * - POST /api/orders/checkout                   (Defaults to standard/naive strategy)
     * - POST /api/orders/checkout?strategy=standard (Naive lost-update reproduction)
     * - POST /api/orders/checkout?strategy=optimistic (JPA @Version optimistic lock)
     * - POST /api/orders/checkout?strategy=pessimistic (Postgres SELECT ... FOR UPDATE)
     */
    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(
            @RequestParam(defaultValue = "standard") String strategy,
            @RequestBody CheckoutRequest request) {
        CheckoutResponse response = checkoutService.checkout(request, strategy);
        return ResponseEntity.ok(response);
    }

    /**
     * Reset SKU stock and clean up test orders for repeatable load testing.
     */
    @PostMapping("/inventory/reset")
    public ResponseEntity<Map<String, Object>> resetInventory(@RequestBody(required = false) InventoryResetRequest request) {
        String sku = (request != null && request.getSku() != null) ? request.getSku() : "GPU-4090";
        int stock = (request != null) ? request.getStock() : 1;

        checkoutService.resetInventory(sku, stock);

        return ResponseEntity.ok(Map.of(
                "sku", sku,
                "stock", stock,
                "message", "Inventory reset successfully"
        ));
    }

    /**
     * Inspect live inventory state and order count.
     */
    @GetMapping("/inventory/status")
    public ResponseEntity<Map<String, Object>> getInventoryStatus(@RequestParam(defaultValue = "GPU-4090") String sku) {
        InventoryItem item = inventoryRepository.findBySku(sku).orElse(null);
        long orderCount = orderRepository.countBySku(sku);

        return ResponseEntity.ok(Map.of(
                "sku", sku,
                "stock", item != null ? item.getStock() : 0,
                "version", (item != null && item.getVersion() != null) ? item.getVersion() : 0,
                "orderCount", orderCount
        ));
    }

    // =========================================================================
    // TOPIC 5: TRANSACTION BOUNDARIES & THE LONG-RUNNING I/O TRAP
    // =========================================================================

    /**
     * Trigger Endpoint: Naive checkout calling 4s external payment HTTP call INSIDE @Transactional.
     * Database connection is held for the entire 4 seconds, starving the HikariCP pool.
     */
    @PostMapping("/checkout-legacy")
    public ResponseEntity<OrderResponse> checkoutLegacy(@RequestBody(required = false) OrderRequest request) {
        OrderRequest req = (request != null) ? request : new OrderRequest();
        OrderResponse response = orderPlacementService.placeOrderLegacy(req);
        return ResponseEntity.ok(response);
    }

    /**
     * Step-by-Step Fix: Decoupled checkout separating DB transactions from network I/O.
     * Database connection is held only during micro-transactions (<10ms).
     */
    @PostMapping("/checkout-decoupled")
    public ResponseEntity<OrderResponse> checkoutDecoupled(@RequestBody(required = false) OrderRequest request) {
        OrderRequest req = (request != null) ? request : new OrderRequest();
        OrderResponse response = orderPlacementService.placeOrder(req);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // TOPIC 6: BATCH PROCESSING & JDBC MEMORY SINKS
    // =========================================================================

    /**
     * Bulk order import supporting:
     * - POST /api/orders/batch-import?strategy=saveall&count=50000 (Naive 1st-level cache sink)
     * - POST /api/orders/batch-import?strategy=batch&count=50000&batchSize=100 (Chunked flush & clear)
     */
    @PostMapping("/batch-import")
    public ResponseEntity<BatchImportResponse> batchImport(
            @RequestParam(defaultValue = "batch") String strategy,
            @RequestParam(defaultValue = "50000") int count,
            @RequestParam(defaultValue = "100") int batchSize) {
        log.info("Received batchImport request: strategy={}, count={}, batchSize={}", strategy, count, batchSize);

        BatchImportResponse response;
        if ("saveall".equalsIgnoreCase(strategy)) {
            response = orderBatchService.importOrdersNaive(count);
        } else {
            response = orderBatchService.importOrdersBatch(count, batchSize);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Purge all batch imported orders for test repeatability.
     */
    @DeleteMapping("/batch-cleanup")
    public ResponseEntity<Map<String, Object>> batchCleanup() {
        orderBatchService.cleanupBatchOrders();
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Batch test orders cleaned up successfully"
        ));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock failure caught: {}", ex.getMessage());
        meterRegistry.counter("checkout.lock.conflicts", "strategy", "optimistic").increment();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", "CONFLICT",
                "error", "OptimisticLockingFailureException",
                "message", "Another transaction updated this item concurrently. Aborting checkout."
        ));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientStock(InsufficientStockException ex) {
        log.warn("Insufficient stock caught: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", "CONFLICT",
                "error", "InsufficientStockException",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<Map<String, Object>> handlePoolStarvation(Exception ex) {
        log.error("HikariCP Pool Starvation Caught: {}", ex.getMessage());
        meterRegistry.counter("hikaricp.pool.starvation.errors").increment();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "SERVICE_UNAVAILABLE",
                "error", "HikariPool-Orders - Connection is not available, request timed out after 1000ms",
                "exception", ex.getClass().getSimpleName(),
                "message", ex.getMessage() != null ? ex.getMessage() : "Database connection pool exhausted due to long-running I/O held in @Transactional"
        ));
    }
}
