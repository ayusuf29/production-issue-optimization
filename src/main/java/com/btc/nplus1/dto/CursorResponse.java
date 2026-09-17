package com.btc.nplus1.dto;

import java.time.Instant;
import java.util.List;

public record CursorResponse<T>(
        List<T> content,
        String nextCursor,
        boolean hasMore
) {}

