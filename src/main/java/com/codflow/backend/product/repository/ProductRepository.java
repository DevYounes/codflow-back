package com.codflow.backend.product.repository;

import com.codflow.backend.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findBySku(String sku);

    Optional<Product> findBySkuIgnoreCase(String sku);

    Optional<Product> findByNameIgnoreCase(String name);

    boolean existsBySku(String sku);

    @Query("SELECT p FROM Product p WHERE p.active = true AND p.alertEnabled = true AND p.currentStock <= p.minThreshold")
    List<Product> findLowStockProducts();

    @Query("SELECT p FROM Product p WHERE p.active = true AND p.currentStock = 0")
    List<Product> findOutOfStockProducts();

    List<Product> findByActiveTrue();

    // Valeur absolue — utilisé par adjustStock et StockArrivalService
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Product p SET p.currentStock = :newStock WHERE p.id = :productId")
    void updateCurrentStock(@Param("productId") Long productId, @Param("newStock") int newStock);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Product p SET p.reservedStock = :newReserved WHERE p.id = :productId")
    void updateReservedStock(@Param("productId") Long productId, @Param("newReserved") int newReserved);

    // Incrément atomique — utilisé par les opérations de réservation de stock
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Product p SET p.currentStock = p.currentStock + :delta WHERE p.id = :productId")
    void incrementCurrentStock(@Param("productId") Long productId, @Param("delta") int delta);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Product p SET p.reservedStock = p.reservedStock + :delta WHERE p.id = :productId")
    void incrementReservedStock(@Param("productId") Long productId, @Param("delta") int delta);
}
