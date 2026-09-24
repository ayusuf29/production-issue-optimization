package com.btc.nplus1.repository;

import com.btc.nplus1.domain.CustomerOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    // 1. Trigger N+1: Standard query without join fetch
    @Query("SELECT o FROM CustomerOrder o ORDER BY o.createdAt DESC")
    List<CustomerOrder> findRecentOrders(Pageable pageable);

    // Paged solutions (Two-phase fetch avoiding in-memory HHH000104 warning)
    @Query("SELECT o.id FROM CustomerOrder o ORDER BY o.createdAt DESC")
    List<Long> findRecentOrderIds(Pageable pageable);

    @Query("SELECT DISTINCT o FROM CustomerOrder o LEFT JOIN FETCH o.items WHERE o.id IN :ids ORDER BY o.createdAt DESC")
    List<CustomerOrder> findOrdersWithJoinFetchByIds(@Param("ids") List<Long> ids);

    @EntityGraph(attributePaths = {"items"})
    @Query("SELECT o FROM CustomerOrder o WHERE o.id IN :ids ORDER BY o.createdAt DESC")
    List<CustomerOrder> findOrdersWithEntityGraphByIds(@Param("ids") List<Long> ids);
}
