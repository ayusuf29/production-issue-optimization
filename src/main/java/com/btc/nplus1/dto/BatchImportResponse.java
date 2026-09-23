package com.btc.nplus1.dto;

public record BatchImportResponse(
        int totalRecords,
        long durationMs,
        double throughputRecordsPerSec,
        String strategy,
        long initialHeapUsedMb,
        long peakHeapUsedMb,
        long maxHeapMb,
        String message
) {}
