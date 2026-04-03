package com.codflow.backend.product.repository;

import com.codflow.backend.product.entity.Product;
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

    /** Returns distinct products that have at least one active variant with stock <= product.minThreshold and alertEnabled. */
    @Query("SELECT DISTINCT v.product FROM ProductVariant v WHERE v.active = true AND v.product.alertEnabled = true AND v.currentStock <= v.product.minThreshold")
    List<Product> findProductsWithLowStockVariants();
}
