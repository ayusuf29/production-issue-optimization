package com.btc.nplus1.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, length = 100)
    private String fullName;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CustomerOrder> orders = new ArrayList<>();

    public User() {}

    public User(String email, String fullName) {
        this.email = email;
        this.fullName = fullName;
    }

    public void addOrder(CustomerOrder order) {
        orders.add(order);
        order.setUser(this);
    }

    // Getters and Setters
    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public List<CustomerOrder> getOrders() { return orders; }
}