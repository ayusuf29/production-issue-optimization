package com.btc.nplus1.dto;

public record PaymentResult(boolean success, String transactionId, String message) {}
