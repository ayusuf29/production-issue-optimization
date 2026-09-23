package com.btc.nplus1.dto;

public class CheckoutResponse {

    private String orderNumber;
    private String sku;
    private int requestedQty;
    private int remainingStock;
    private String status;
    private String strategy;
    private String message;

    public CheckoutResponse() {}

    public CheckoutResponse(String orderNumber, String sku, int requestedQty, int remainingStock, String status, String strategy, String message) {
        this.orderNumber = orderNumber;
        this.sku = sku;
        this.requestedQty = requestedQty;
        this.remainingStock = remainingStock;
        this.status = status;
        this.strategy = strategy;
        this.message = message;
    }

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public int getRequestedQty() { return requestedQty; }
    public void setRequestedQty(int requestedQty) { this.requestedQty = requestedQty; }
    public int getRemainingStock() { return remainingStock; }
    public void setRemainingStock(int remainingStock) { this.remainingStock = remainingStock; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
