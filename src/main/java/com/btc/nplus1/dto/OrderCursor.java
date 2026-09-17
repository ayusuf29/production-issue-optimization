package com.btc.nplus1.dto;

import java.time.Instant;

public record OrderCursor(
        Instant createdAt,
        Long id
) {}
