package com.btc.nplus1.dto;

import java.time.Instant;

public record OrderSummaryResponse(
        Long id,
        String orderNumber,
        Instant createdAt
) {}