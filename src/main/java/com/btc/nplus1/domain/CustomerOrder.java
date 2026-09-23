package com.btc.nplus1.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customer_orders", indexes = {
        @Index(name = "idx_orders_created_id", columnList = "created_at DESC, id DESC"),
        @Index(name = "idx_orders_sku", columnList = "sku")
})
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String orderNumber;

    @Column(length = 64)
    private String sku;

    @Column(length = 32)
    private String status = "COMPLETED";

    @Column(nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    public CustomerOrder() {}

    public CustomerOrder(String orderNumber, Instant createdAt) {
        this.orderNumber = orderNumber;
        this.createdAt = createdAt;
    }

    public CustomerOrder(String orderNumber, String sku, Instant createdAt, User user) {
        this.orderNumber = orderNumber;
        this.sku = sku;
        this.createdAt = createdAt;
        this.user = user;
    }

    public CustomerOrder(String orderNumber, String sku, String status, Instant createdAt, User user) {
        this.orderNumber = orderNumber;
        this.sku = sku;
        this.status = status;
        this.createdAt = createdAt;
        this.user = user;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    // Getters and Setters
    public Long getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public List<OrderItem> getItems() { return items; }
}
