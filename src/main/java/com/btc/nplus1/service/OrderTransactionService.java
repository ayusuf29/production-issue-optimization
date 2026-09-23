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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class OrderTransactionService {

    private static final Logger log = LoggerFactory.getLogger(OrderTransactionService.class);

    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public OrderTransactionService(InventoryRepository inventoryRepository,
                                  OrderRepository orderRepository,
                                  UserRepository userRepository) {
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    /**
     * 1. Short DB transaction to reserve state.
     * Connection is held only for ~2-5ms while acquiring lock, updating stock, and creating pending order.
     */
    @Observed(name = "order.tx.createPending", contextualName = "OrderTransactionService#createPendingOrder")
    @Transactional
    public Long createPendingOrder(OrderRequest request) {
        long txStart = System.currentTimeMillis();
        log.info("[TX-1: PENDING ORDER] Reserving stock and creating pending order for SKU: {}", request.getSku());

        InventoryItem item = inventoryRepository.findBySkuForUpdate(request.getSku())
                .orElseThrow(() -> new EntityNotFoundException("SKU not found: " + request.getSku()));

        if (!item.hasStock(request.getQty())) {
            throw new InsufficientStockException("Out of stock for SKU: " + request.getSku());
        }

        item.deductStock(request.getQty());
        inventoryRepository.save(item);

        User user = userRepository.findAll().stream().findFirst()
                .orElseGet(() -> userRepository.save(new User("buyer@btc.com", "Flash Sale Buyer")));

        String orderNumber = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        CustomerOrder order = new CustomerOrder(orderNumber, request.getSku(), "PENDING_PAYMENT", Instant.now(), user);
        order.addItem(new OrderItem(request.getSku(), request.getQty(), BigDecimal.valueOf(1999.99)));
        orderRepository.save(order);

        long txDuration = System.currentTimeMillis() - txStart;
        log.info("[TX-1: PENDING ORDER] Committed! Order ID: {}, Status: PENDING_PAYMENT (DB Conn held {}ms -> Released to pool)",
                order.getId(), txDuration);
        return order.getId();
    }

    /**
     * 3. Short DB transaction to record final payment status.
     * Connection is held only for ~2-5ms while updating order status to PAID or PAYMENT_FAILED.
     */
    @Observed(name = "order.tx.finalize", contextualName = "OrderTransactionService#finalizeOrder")
    @Transactional
    public OrderResponse finalizeOrder(Long orderId, PaymentResult payment) {
        long txStart = System.currentTimeMillis();
        log.info("[TX-2: FINALIZE ORDER] Updating payment status for Order ID: {}", orderId);

        CustomerOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));

        if (payment != null && payment.success()) {
            order.setStatus("PAID");
        } else {
            order.setStatus("PAYMENT_FAILED");
            // Compensating action: restore inventory item stock if payment failed
            inventoryRepository.findBySku(order.getSku()).ifPresent(item -> {
                int qty = order.getItems().isEmpty() ? 1 : order.getItems().get(0).getQuantity();
                item.setStock(item.getStock() + qty);
                inventoryRepository.save(item);
            });
        }
        orderRepository.save(order);

        long txDuration = System.currentTimeMillis() - txStart;
        long totalElapsedMs = Duration.between(order.getCreatedAt(), Instant.now()).toMillis();

        log.info("[TX-2: FINALIZE ORDER] Committed! Order ID: {}, Status: {} (DB Conn held {}ms -> Released to pool, Total order cycle: {}ms)",
                order.getId(), order.getStatus(), txDuration, totalElapsedMs);

        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getSku(),
                order.getItems().isEmpty() ? 1 : order.getItems().get(0).getQuantity(),
                order.getStatus(),
                "decoupled",
                totalElapsedMs,
                "Order finalized successfully. DB connections were held only during quick boundary transitions (<10ms)!"
        );
    }
}
