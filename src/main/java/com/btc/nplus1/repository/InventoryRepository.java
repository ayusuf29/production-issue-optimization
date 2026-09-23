package com.btc.nplus1.repository;

import com.btc.nplus1.domain.InventoryItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {

    // Standard read (No lock, what most developers do)
    Optional<InventoryItem> findBySku(String sku);

    // Option B: Pessimistic Row Lock (SELECT ... FOR UPDATE)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InventoryItem i WHERE i.sku = :sku")
    Optional<InventoryItem> findBySkuForUpdate(@Param("sku") String sku);

    // Unsafe direct update bypassing @Version check to cleanly reproduce the naive lost update / race condition
    @Modifying
    @Query(value = "UPDATE inventory SET stock = :stock WHERE id = :id", nativeQuery = true)
    void updateStockUnsafe(@Param("id") Long id, @Param("stock") int stock);

    // Defense-in-depth: Atomic conditional update
    @Modifying
    @Query("UPDATE InventoryItem i SET i.stock = i.stock - :qty WHERE i.sku = :sku AND i.stock >= :qty")
    int deductStockDirect(@Param("sku") String sku, @Param("qty") int qty);
}
