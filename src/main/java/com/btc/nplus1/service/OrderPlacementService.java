package com.btc.nplus1.service;

import com.btc.nplus1.domain.CustomerOrder;
import com.btc.nplus1.domain.InventoryItem;
import com.btc.nplus1.domain.OrderItem;
import com.btc.nplus1.domain.User;
import com.btc.nplus1.dto.OrderRequest;
import com.btc.nplus1.dto.OrderResponse;
import com.btc.nplus1.dto.PaymentResult;
import com.btc.nplus1.exception.InsufficientStockException;
import com.btc.nplus1.repository.InventoryRepository;
import com.btc.nplus1.repository.OrderRepository;
import com.btc.nplus1.repository.UserRepository;
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
public class OrderPlacementService {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);

    private final OrderTransactionService txService;
    private final PaymentClient paymentClient;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public OrderPlacementService(OrderTransactionService txService,
                                 PaymentClient paymentClient,
                                 InventoryRepository inventoryRepository,
                                 OrderRepository orderRepository,
                                 UserRepository userRepository) {
        this.txService = txService;
        this.paymentClient = paymentClient;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    /**
     * TOPIC 5: THE PRODUCTION FAILURE (The Long-Running I/O Trap)
     * A service method marked with @Transactional calls a third-party payment gateway over HTTP.
     * When the payment gateway takes 4 seconds to respond, the database connection is held open
     * for the entire duration, starving the HikariCP pool and crashing unrelated endpoints.
     */
    @Observed(name = "checkout.legacy", contextualName = "OrderPlacementService#placeOrderLegacy")
    @Transactional
    public OrderResponse placeOrderLegacy(OrderRequest request) {
        long startMs = System.currentTimeMillis();
        log.warn("[LEGACY CHECKOUT - DISASTER TRAP] Starting @Transactional order placement. HikariCP connection acquired and locked!");

        // 1. Initial database query (pins physical connection from HikariPool-Orders)
        InventoryItem item = inventoryRepository.findBySku(request.getSku())
                .orElseThrow(() -> new EntityNotFoundException("SKU not found: " + request.getSku()));

        if (!item.hasStock(request.getQty())) {
            throw new InsufficientStockException("Out of stock for SKU: " + request.getSku());
        }

        item.deductStock(request.getQty());
        inventoryRepository.save(item);

        User user = userRepository.findAll().stream().findFirst()
                .orElseGet(() -> userRepository.save(new User("buyer@btc.com", "Flash Sale Buyer")));

        String orderNumber = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        CustomerOrder order = new CustomerOrder(orderNumber, request.getSku(), "PROCESSING", Instant.now(), user);
        order.addItem(new OrderItem(request.getSku(), request.getQty(), BigDecimal.valueOf(1999.99)));
        orderRepository.save(order);

        // 2. THE PRODUCTION CATASTROPHE: Calling external HTTP payment gateway inside @Transactional
        log.error("[LEGACY CHECKOUT - DISASTER TRAP] Executing 4000ms external payment HTTP call INSIDE active @Transactional! DB connection is pinned to this thread!");
        PaymentResult payment = paymentClient.processPayment(request.paymentDetails());

        if (payment != null && payment.success()) {
            order.setStatus("PAID");
        } else {
            order.setStatus("PAYMENT_FAILED");
        }
        orderRepository.save(order);

        long totalDurationMs = System.currentTimeMillis() - startMs;
        log.info("[LEGACY CHECKOUT] @Transactional method completed after {}ms. Hikari connection finally released.", totalDurationMs);

        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getSku(),
                request.getQty(),
                order.getStatus(),
                "legacy",
                totalDurationMs,
                "Legacy checkout succeeded, BUT held a HikariCP connection open for " + totalDurationMs + "ms!"
        );
    }

    /**
     * TOPIC 5: THE STEP-BY-STEP FIX
     * Separate DB Transactions from Network I/O:
     * - Method is NOT @Transactional.
     * - Step 1: Short DB transaction to reserve state.
     * - Step 2: External I/O executed with NO database connection held.
     * - Step 3: Short DB transaction to record final payment status.
     */
    @Observed(name = "checkout.decoupled", contextualName = "OrderPlacementService#placeOrder")
    public OrderResponse placeOrder(OrderRequest request) {
        log.info("[DECOUPLED CHECKOUT - ENTERPRISE FIX] Initiating non-transactional orchestrator. Zero DB connections held.");

        // 1. Short DB transaction to reserve state
        Long orderId = txService.createPendingOrder(request);

        // 2. External I/O executed with NO database connection held
        log.info("[DECOUPLED CHECKOUT - ENTERPRISE FIX] Calling 4000ms payment gateway with ZERO database connections held!");
        PaymentResult payment = paymentClient.processPayment(request.paymentDetails());

        // 3. Short DB transaction to record final payment status
        return txService.finalizeOrder(orderId, payment);
    }
}
