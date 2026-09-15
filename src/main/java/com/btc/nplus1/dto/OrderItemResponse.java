package com.btc.nplus1.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        String sku,
        Integer quantity,
        BigDecimal unitPrice
) {}
