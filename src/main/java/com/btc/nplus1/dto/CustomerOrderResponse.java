package com.btc.nplus1.dto;

import java.time.Instant;
import java.util.List;

public record CustomerOrderResponse(
        Long id,
        String orderNumber,
        Instant createdAt,
        int totalItems,
        List<OrderItemResponse> items
) {}
