package com.btc.nplus1.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "inventory", indexes = {
        @Index(name = "idx_inventory_sku", columnList = "sku", unique = true)
})
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false)
    private Integer stock;

    @Version
    private Long version;

    public InventoryItem() {}

    public InventoryItem(String sku, Integer stock) {
        this.sku = sku;
        this.stock = stock;
    }

    public boolean hasStock(int requestedQty) {
        return this.stock != null && this.stock >= requestedQty;
    }

    public void deductStock(int qty) {
        if (this.stock < qty) {
            throw new IllegalStateException("Cannot deduct " + qty + " from available stock of " + this.stock);
        }
        this.stock -= qty;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
