package com.btc.nplus1.service;

import com.btc.nplus1.domain.CustomerOrder;
import com.btc.nplus1.dto.*;
import com.btc.nplus1.repository.OrderRepository;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Observed(name = "order.service.getOrders", contextualName = "OrderService#getOrders")
    @Transactional(readOnly = true)
    public List<CustomerOrderResponse> getOrders(String strategy, int limit) {
        List<CustomerOrder> orders;

        switch (strategy.toLowerCase()) {
            case "joinfetch" ->
                    orders = orderRepository.findRecentOrdersWithJoinFetch();
            case "entitygraph" ->
                    orders = orderRepository.findRecentOrdersWithEntityGraph();
            default ->
                // Default triggers N+1 via standard lazy collection access
                    orders = orderRepository.findRecentOrders(PageRequest.of(0, limit));
        }

        // Mapping to DTO: accessing o.getItems() is what triggers the child queries in N+1!
        return orders.stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Observed(name = "order.service.getSummary", contextualName = "OrderService#getSummary")
    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getSummary(){
        // 1. Fetches only the CustomerOrder table
        List<CustomerOrder> orders = orderRepository.findAll();

        // 2. Read only parent properties — getItems() is NEVER touched
        return orders.stream()
                .map(order -> new OrderSummaryResponse(
                        order.getId(),
                        order.getOrderNumber(),
                        order.getCreatedAt()
                ))
                .toList();
    }

    @Observed(name = "order.service.offset", contextualName = "OrderService#getOrdersOffset")
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> getOrdersOffset(int page, int size) {
        Page<CustomerOrder> orderPage = orderRepository.findAllOffset(PageRequest.of(page, size));
        return orderPage.map(this::toSummary);
    }

    @Observed(name = "order.service.keyset", contextualName = "OrderService#getOrdersKeyset")
    @Transactional(readOnly = true)
    public CursorResponse<OrderSummaryResponse> getOrdersKeyset(String cursorToken, int size) {
        // Fetch size + 1 to check if there is a next page without a separate count query
        PageRequest limit = PageRequest.of(0, size + 1);
        List<CustomerOrder> orders;

        if (cursorToken == null || cursorToken.isBlank()) {
            orders = orderRepository.findFirstKeysetPage(limit);
        } else {
            OrderCursor cursor = decodeCursor(cursorToken);
            orders = orderRepository.findNextKeysetPage(cursor.createdAt(), cursor.id(), limit);
        }

        boolean hasMore = orders.size() > size;
        List<CustomerOrder> results = hasMore ? orders.subList(0, size) : orders;

        String nextCursor = null;
        if (hasMore && !results.isEmpty()) {
            CustomerOrder last = results.get(results.size() - 1);
            nextCursor = encodeCursor(new OrderCursor(last.getCreatedAt(), last.getId()));
        }

        return new CursorResponse<>(results.stream().map(this::toSummary).toList(), nextCursor, hasMore);
    }

    @Observed(name = "order.service.deferred", contextualName = "OrderService#getOrdersDeferred")
    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getOrdersDeferred(int page, int size) {
        int offset = page * size;
        return orderRepository.findByDeferredJoin(offset, size).stream()
                .map(this::toSummary)
                .toList();
    }

    private OrderSummaryResponse toSummary(CustomerOrder o) {
        return new OrderSummaryResponse(o.getId(), o.getOrderNumber(), o.getCreatedAt());
    }

    private String encodeCursor(OrderCursor cursor) {
        String raw = cursor.createdAt().toEpochMilli() + ":" + cursor.id();
        return Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private OrderCursor decodeCursor(String token) {
        String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        String[] parts = raw.split(":");
        return new OrderCursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
    }

    private CustomerOrderResponse mapToResponse(CustomerOrder order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getSku(),
                        item.getQuantity(),
                        item.getUnitPrice()
                ))
                .toList();

        return new CustomerOrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getCreatedAt(),
                itemResponses.size(),
                itemResponses
        );
    }
}
