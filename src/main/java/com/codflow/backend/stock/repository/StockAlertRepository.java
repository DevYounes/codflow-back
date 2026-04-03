package com.codflow.backend.stock.repository;

import com.codflow.backend.stock.entity.StockAlert;
import com.codflow.backend.stock.enums.AlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {

    List<StockAlert> findByResolvedFalse();

    Page<StockAlert> findByResolved(boolean resolved, Pageable pageable);

    /** For product-level alerts (no variant). */
    boolean existsByProductIdAndVariantIsNullAndAlertTypeAndResolvedFalse(Long productId, AlertType alertType);

    /** For variant-level alerts. */
    boolean existsByProductIdAndVariantIdAndAlertTypeAndResolvedFalse(Long productId, Long variantId, AlertType alertType);

    List<StockAlert> findByProductIdAndResolvedFalse(Long productId);

    @Query("SELECT a FROM StockAlert a WHERE a.product.id = :productId AND a.variant.id = :variantId AND a.resolved = false")
    List<StockAlert> findByProductIdAndVariantIdAndResolvedFalse(@Param("productId") Long productId, @Param("variantId") Long variantId);
}
