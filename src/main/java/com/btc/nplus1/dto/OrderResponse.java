package com.btc.nplus1.dto;

public record OrderResponse(
        Long orderId,
        String orderNumber,
        String sku,
        int qty,
        String status,
        String strategy,
        long totalDurationMs,
        String message
) {}
