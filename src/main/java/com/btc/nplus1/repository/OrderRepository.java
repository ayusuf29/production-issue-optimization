package com.btc.nplus1.repository;


import com.btc.nplus1.domain.CustomerOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    // 1. Trigger N+1: Standard query without join fetch
    @Query("SELECT o FROM CustomerOrder o ORDER BY o.createdAt DESC")
    List<CustomerOrder> findRecentOrders(Pageable pageable);

    // 2. Solution 1: Explicit JPQL JOIN FETCH with DISTINCT
    @Query("SELECT DISTINCT o FROM CustomerOrder o LEFT JOIN FETCH o.items ORDER BY o.createdAt DESC")
    List<CustomerOrder> findRecentOrdersWithJoinFetch();

    // 3. Solution 2: Declarative @EntityGraph
    @EntityGraph(attributePaths = {"items"})
    @Query("SELECT o FROM CustomerOrder o ORDER BY o.createdAt DESC")
    List<CustomerOrder> findRecentOrdersWithEntityGraph();
}
