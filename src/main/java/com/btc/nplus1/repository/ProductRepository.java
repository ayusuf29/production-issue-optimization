package com.btc.nplus1.repository;

import com.btc.nplus1.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, String> {

    @Query("SELECT p FROM Product p JOIN FETCH p.category c LEFT JOIN FETCH p.details d WHERE p.id = :id")
    Optional<Product> findProductWithDetailsById(@Param("id") String id);
}
