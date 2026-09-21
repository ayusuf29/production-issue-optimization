package com.btc.nplus1.service;

import com.btc.nplus1.domain.CustomerOrder;
import com.btc.nplus1.dto.CustomerOrderResponse;
import com.btc.nplus1.dto.OrderItemResponse;
import com.btc.nplus1.dto.OrderSummaryResponse;
import com.btc.nplus1.repository.OrderRepository;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        Pageable pageable = PageRequest.of(0, limit);
        List<CustomerOrder> orders;

        switch (strategy.toLowerCase()) {
            case "joinfetch" -> {
                List<Long> ids = orderRepository.findRecentOrderIds(pageable);
                orders = ids.isEmpty() ? List.of() : orderRepository.findOrdersWithJoinFetchByIds(ids);
            }
            case "entitygraph" -> {
                List<Long> ids = orderRepository.findRecentOrderIds(pageable);
                orders = ids.isEmpty() ? List.of() : orderRepository.findOrdersWithEntityGraphByIds(ids);
            }
            default ->
                // Default triggers N+1 via standard lazy collection access
                orders = orderRepository.findRecentOrders(pageable);
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
