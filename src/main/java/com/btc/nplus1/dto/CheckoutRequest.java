package com.btc.nplus1.dto;

public class CheckoutRequest {

    private String sku;
    private int qty = 1;

    public CheckoutRequest() {}

    public CheckoutRequest(String sku, int qty) {
        this.sku = sku;
        this.qty = qty;
    }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }
}
