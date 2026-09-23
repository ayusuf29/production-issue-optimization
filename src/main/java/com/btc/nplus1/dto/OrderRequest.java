package com.btc.nplus1.dto;

public class OrderRequest {

    private String sku = "GPU-4090";
    private int qty = 1;
    private PaymentDetails paymentDetails = new PaymentDetails("4111222233334444", 1999.99);

    public OrderRequest() {}

    public OrderRequest(String sku, int qty) {
        this.sku = sku;
        this.qty = qty;
        this.paymentDetails = new PaymentDetails("4111222233334444", 1999.99 * qty);
    }

    public OrderRequest(String sku, int qty, PaymentDetails paymentDetails) {
        this.sku = sku;
        this.qty = qty;
        this.paymentDetails = paymentDetails != null ? paymentDetails : new PaymentDetails("4111222233334444", 1999.99 * qty);
    }

    public String getSku() {
        return (sku != null && !sku.isBlank()) ? sku : "GPU-4090";
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public int getQty() {
        return qty > 0 ? qty : 1;
    }

    public void setQty(int qty) {
        this.qty = qty;
    }

    public PaymentDetails paymentDetails() {
        return paymentDetails != null ? paymentDetails : new PaymentDetails("4111222233334444", 1999.99 * getQty());
    }

    public PaymentDetails getPaymentDetails() {
        return paymentDetails();
    }

    public void setPaymentDetails(PaymentDetails paymentDetails) {
        this.paymentDetails = paymentDetails;
    }
}
