package com.codflow.backend.product.repository;

import com.codflow.backend.product.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductIdAndActiveTrue(Long productId);

    List<ProductVariant> findByProductId(Long productId);

    boolean existsByProductIdAndColorAndSize(Long productId, String color, String size);

    @Modifying
    @Query("UPDATE ProductVariant v SET v.currentStock = :newStock WHERE v.id = :variantId")
    void updateCurrentStock(@Param("variantId") Long variantId, @Param("newStock") int newStock);

    java.util.Optional<ProductVariant> findByVariantSku(String variantSku);
}
