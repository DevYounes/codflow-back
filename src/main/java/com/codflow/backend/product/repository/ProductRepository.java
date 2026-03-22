package com.codflow.backend.product.repository;

import com.codflow.backend.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    @Query("SELECT p FROM Product p WHERE p.active = true AND " +
           "(:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Product> searchProducts(String search, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.active = true AND p.alertEnabled = true AND p.currentStock <= p.minThreshold")
    List<Product> findLowStockProducts();

    @Query("SELECT p FROM Product p WHERE p.active = true AND p.currentStock = 0")
    List<Product> findOutOfStockProducts();

    List<Product> findByActiveTrue();
}
