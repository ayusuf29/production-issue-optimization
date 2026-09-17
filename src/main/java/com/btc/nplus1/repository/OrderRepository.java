package com.btc.nplus1.repository;


import com.btc.nplus1.domain.CustomerOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    // 1. Standard Offset Pagination (Triggers full scans + count query)
    @Query("SELECT o FROM CustomerOrder o ORDER BY o.createdAt DESC, o.id DESC")
    Page<CustomerOrder> findAllOffset(Pageable pageable);

    // 2. Keyset: Initial Page Fetch
    @Query("SELECT o FROM CustomerOrder o ORDER BY o.createdAt DESC, o.id DESC")
    List<CustomerOrder> findFirstKeysetPage(Pageable pageable);

    // 3. Keyset: Subsequent Page Fetch (B-Tree index seek)
    @Query("""
        SELECT o FROM CustomerOrder o
        WHERE (o.createdAt < :createdAt)
           OR (o.createdAt = :createdAt AND o.id < :id)
        ORDER BY o.createdAt DESC, o.id DESC
    """)
    List<CustomerOrder> findNextKeysetPage(
            @Param("createdAt") Instant createdAt,
            @Param("id") Long id,
            Pageable pageable
        );

        // 4. Deferred Join: Keyset Seek using Index Subquery for Arbitrary Page Jumps
        @Query(value = """
            WITH target_cursor AS (
                SELECT created_at, id
                FROM customer_orders
                ORDER BY created_at DESC, id DESC
                LIMIT 1 OFFSET :offset
            )
            SELECT o.*
            FROM customer_orders o, target_cursor c
            WHERE (o.created_at, o.id) <= (c.created_at, c.id)
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT :limit
        """, nativeQuery = true)
        List<CustomerOrder> findByDeferredJoin(
                @Param("offset") int offset,
                @Param("limit") int limit
        );

}
