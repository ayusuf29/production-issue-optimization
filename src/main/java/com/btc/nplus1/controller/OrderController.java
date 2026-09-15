package com.btc.nplus1.controller;

import com.btc.nplus1.dto.CustomerOrderResponse;
import com.btc.nplus1.dto.OrderSummaryResponse;
import com.btc.nplus1.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
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

        log.info("Received getOrders request: strategy={}, limit={}", strategy, limit);
        List<CustomerOrderResponse> response = orderService.getOrders(strategy, limit);
        log.info("Finished getOrders request: returning {} items", response.size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public ResponseEntity<List<OrderSummaryResponse>> getOrderSummaries() {
        log.info("Received getOrderSummaries request");
        return ResponseEntity.ok(orderService.getSummary());
    }
}
