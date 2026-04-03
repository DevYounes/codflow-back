package com.codflow.backend.stock.repository;

import com.codflow.backend.stock.entity.StockArrivalItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StockArrivalItemRepository extends JpaRepository<StockArrivalItem, Long> {

    /** Nullify variant reference when a variant is deleted (preserves arrival history). */
    @Modifying
    @Query("UPDATE StockArrivalItem i SET i.variant = null WHERE i.variant.id = :variantId")
    void nullifyVariant(@Param("variantId") Long variantId);
}
