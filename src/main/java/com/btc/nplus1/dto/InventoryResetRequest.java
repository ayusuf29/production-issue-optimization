package com.btc.nplus1.dto;

public class InventoryResetRequest {

    private String sku = "GPU-4090";
    private int stock = 1;

    public InventoryResetRequest() {}

    public InventoryResetRequest(String sku, int stock) {
        this.sku = sku;
        this.stock = stock;
    }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }
}
