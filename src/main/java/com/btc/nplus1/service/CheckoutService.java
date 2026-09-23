package com.btc.nplus1.service;

import com.btc.nplus1.domain.CustomerOrder;
import com.btc.nplus1.domain.InventoryItem;
import com.btc.nplus1.domain.OrderItem;
import com.btc.nplus1.domain.User;
import com.btc.nplus1.dto.CheckoutRequest;
import com.btc.nplus1.dto.CheckoutResponse;
import com.btc.nplus1.exception.InsufficientStockException;
import com.btc.nplus1.repository.InventoryRepository;
import com.btc.nplus1.repository.OrderRepository;
import com.btc.nplus1.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final MeterRegistry meterRegistry;

    // Micrometer Telemetry Counters
    private final Counter standardSuccessCounter;
    private final Counter optimisticSuccessCounter;
    private final Counter pessimisticSuccessCounter;
    private final Counter oversellCounter;
    private final Counter stockExhaustedCounter;

    public CheckoutService(InventoryRepository inventoryRepository,
                           OrderRepository orderRepository,
                           UserRepository userRepository,
                           MeterRegistry meterRegistry) {
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.meterRegistry = meterRegistry;

        this.standardSuccessCounter = meterRegistry.counter("checkout.orders.created", "strategy", "standard");
        this.optimisticSuccessCounter = meterRegistry.counter("checkout.orders.created", "strategy", "optimistic");
        this.pessimisticSuccessCounter = meterRegistry.counter("checkout.orders.created", "strategy", "pessimistic");
        this.oversellCounter = meterRegistry.counter("checkout.oversell.detected", "strategy", "standard");
        this.stockExhaustedCounter = meterRegistry.counter("checkout.stock.exhausted");
    }

    /**
     * Dispatcher method based on concurrency strategy:
     * - "standard": The naive way (No lock, read-modify-write lost update)
     * - "optimistic": Option A (JPA @Version optimistic locking)
     * - "pessimistic": Option B (PostgreSQL SELECT ... FOR UPDATE pessimistic row locking)
     */
    public CheckoutResponse checkout(CheckoutRequest req, String strategy) {
        String strat = (strategy == null || strategy.isBlank()) ? "standard" : strategy.trim().toLowerCase();
        return switch (strat) {
            case "optimistic" -> checkoutOptimistic(req);
            case "pessimistic" -> checkoutPessimistic(req);
            default -> checkoutStandard(req);
        };
    }

    /**
     * 1. The Standard Way (What 95% of Developers Do):
     * Naive Read-Modify-Write inside @Transactional.
     * With READ COMMITTED isolation and no lock, both concurrent threads read stock=1,
     * calculate 1-1=0, and commit. Result: 2 orders created, stock oversold!
     */
    @Observed(name = "checkout.service.standard", contextualName = "CheckoutService#checkoutStandard")
    @Transactional
    public CheckoutResponse checkoutStandard(CheckoutRequest req) {
        log.info("[STANDARD CHECKOUT] Processing order for SKU: {}, qty: {}", req.getSku(), req.getQty());

        // 1. Read without lock
        InventoryItem item = inventoryRepository.findBySku(req.getSku())
                .orElseThrow(() -> new EntityNotFoundException("Item not found for SKU: " + req.getSku()));

        // 2. In-memory stock validation
        if (item.getStock() < req.getQty()) {
            stockExhaustedCounter.increment();
            log.warn("[STANDARD CHECKOUT] Out of stock for SKU: {}, available: {}, requested: {}",
                    req.getSku(), item.getStock(), req.getQty());
            throw new InsufficientStockException("Out of stock for SKU: " + req.getSku());
        }

        // 3. Simulated business latency (e.g. payment auth, validation window)
        // This expands the concurrency race window reliably during demonstrations
        simulateProcessingDelay(50);

        // Check if an order already exists while stock was 1 (silent oversell detection for telemetry)
        long currentOrderCount = orderRepository.countBySku(req.getSku());
        if (currentOrderCount >= 1) {
            oversellCounter.increment();
            log.error("[STANDARD CHECKOUT] CRITICAL ALERT: OVERSELL DETECTED! Existing orders: {}, creating another!", currentOrderCount);
        }

        // 4. In-memory deduction & unsafe update (simulates standard non-versioned update)
        int updatedStock = item.getStock() - req.getQty();
        inventoryRepository.updateStockUnsafe(item.getId(), updatedStock);

        // 5. Create Order
        CustomerOrder order = createOrderRecord(item.getSku(), req.getQty());
        standardSuccessCounter.increment();

        log.info("[STANDARD CHECKOUT] SUCCESS! Created order: {}, remaining stock recorded: {}",
                order.getOrderNumber(), updatedStock);

        return new CheckoutResponse(
                order.getOrderNumber(),
                item.getSku(),
                req.getQty(),
                updatedStock,
                "SUCCESS",
                "standard",
                "Standard checkout succeeded (Naive - vulnerable to lost updates)"
        );
    }

    /**
     * 2. Optimization A: Optimistic Locking (Low Contention)
     * Relies on JPA @Version. Hibernate checks `WHERE id = ? AND version = ?`.
     * If another transaction committed first, OptimisticLockingFailureException is thrown.
     */
    @Observed(name = "checkout.service.optimistic", contextualName = "CheckoutService#checkoutOptimistic")
    @Transactional
    public CheckoutResponse checkoutOptimistic(CheckoutRequest req) {
        log.info("[OPTIMISTIC CHECKOUT] Processing order for SKU: {}, qty: {}", req.getSku(), req.getQty());

        // 1. Read entity with version tracking
        InventoryItem item = inventoryRepository.findBySku(req.getSku())
                .orElseThrow(() -> new EntityNotFoundException("Item not found for SKU: " + req.getSku()));

        // 2. In-memory validation
        if (!item.hasStock(req.getQty())) {
            stockExhaustedCounter.increment();
            log.warn("[OPTIMISTIC CHECKOUT] Out of stock for SKU: {}, available: {}, requested: {}",
                    req.getSku(), item.getStock(), req.getQty());
            throw new InsufficientStockException("Out of stock for SKU: " + req.getSku());
        }

        // 3. Simulated business latency
        simulateProcessingDelay(50);

        // 4. Deduct and save via JPA (triggers @Version check on flush/commit)
        item.deductStock(req.getQty());
        inventoryRepository.save(item);

        // 5. Create Order
        CustomerOrder order = createOrderRecord(item.getSku(), req.getQty());
        optimisticSuccessCounter.increment();

        log.info("[OPTIMISTIC CHECKOUT] SUCCESS! Created order: {}, version: {}",
                order.getOrderNumber(), item.getVersion());

        return new CheckoutResponse(
                order.getOrderNumber(),
                item.getSku(),
                req.getQty(),
                item.getStock(),
                "SUCCESS",
                "optimistic",
                "Optimistic checkout succeeded with version check"
        );
    }

    /**
     * 3. Optimization B: Pessimistic Row Locking (High Contention Flash Sales)
     * Issues `SELECT ... FOR UPDATE` row lock in PostgreSQL.
     * The first transaction locks the row; concurrent transactions are suspended by Postgres.
     * When the second transaction unblocks, it reads the freshly committed stock=0 and aborts safely.
     */
    @Observed(name = "checkout.service.pessimistic", contextualName = "CheckoutService#checkoutPessimistic")
    @Transactional
    public CheckoutResponse checkoutPessimistic(CheckoutRequest req) {
        log.info("[PESSIMISTIC CHECKOUT] Acquiring row lock for SKU: {}, qty: {}", req.getSku(), req.getQty());

        // 1. Acquire exclusive row lock in DB: SELECT ... FOR UPDATE
        InventoryItem item = inventoryRepository.findBySkuForUpdate(req.getSku())
                .orElseThrow(() -> new EntityNotFoundException("Item not found for SKU: " + req.getSku()));

        log.info("[PESSIMISTIC CHECKOUT] Row lock acquired for SKU: {}, current stock: {}",
                item.getSku(), item.getStock());

        // 2. Validate fresh stock while holding exclusive lock
        if (!item.hasStock(req.getQty())) {
            stockExhaustedCounter.increment();
            log.warn("[PESSIMISTIC CHECKOUT] Out of stock for SKU: {}, available: {}, requested: {}",
                    req.getSku(), item.getStock(), req.getQty());
            throw new InsufficientStockException("Out of stock for SKU: " + req.getSku());
        }

        // 3. Simulated business latency
        simulateProcessingDelay(50);

        // 4. Deduct stock safely
        item.deductStock(req.getQty());
        inventoryRepository.save(item);

        // 5. Create Order
        CustomerOrder order = createOrderRecord(item.getSku(), req.getQty());
        pessimisticSuccessCounter.increment();

        log.info("[PESSIMISTIC CHECKOUT] SUCCESS! Created order: {}, remaining stock: {}",
                order.getOrderNumber(), item.getStock());

        return new CheckoutResponse(
                order.getOrderNumber(),
                item.getSku(),
                req.getQty(),
                item.getStock(),
                "SUCCESS",
                "pessimistic",
                "Pessimistic checkout succeeded with DB row lock (SELECT ... FOR UPDATE)"
        );
    }

    /**
     * Helper to reset SKU stock and clear test orders for reproducible demonstrations.
     */
    @Transactional
    public void resetInventory(String sku, int stock) {
        orderRepository.deleteBySku(sku);

        InventoryItem item = inventoryRepository.findBySku(sku)
                .orElseGet(() -> new InventoryItem(sku, stock));

        item.setStock(stock);
        item.setVersion(0L);
        inventoryRepository.save(item);

        log.info("Reset inventory for SKU: {} to stock: {}, version: 0, orders cleared.", sku, stock);
    }

    private CustomerOrder createOrderRecord(String sku, int qty) {
        User user = userRepository.findAll().stream().findFirst()
                .orElseGet(() -> userRepository.save(new User("buyer@btc.com", "Flash Sale Buyer")));

        String orderNumber = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        CustomerOrder order = new CustomerOrder(orderNumber, sku, Instant.now(), user);
        order.addItem(new OrderItem(sku, qty, BigDecimal.valueOf(1999.99)));
        return orderRepository.save(order);
    }

    private void simulateProcessingDelay(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
