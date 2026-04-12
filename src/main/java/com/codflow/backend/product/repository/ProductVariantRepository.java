package com.codflow.backend.product.repository;

import com.codflow.backend.product.entity.Product;
import com.codflow.backend.product.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductIdAndActiveTrue(Long productId);

    List<ProductVariant> findByProductId(Long productId);

    boolean existsByProductIdAndColorAndSize(Long productId, String color, String size);

    // Valeur absolue — utilisé par adjustStock et StockArrivalService
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ProductVariant v SET v.currentStock = :newStock WHERE v.id = :variantId")
    void updateCurrentStock(@Param("variantId") Long variantId, @Param("newStock") int newStock);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ProductVariant v SET v.reservedStock = :newReserved WHERE v.id = :variantId")
    void updateReservedStock(@Param("variantId") Long variantId, @Param("newReserved") int newReserved);

    // Incrément atomique — utilisé par les opérations de réservation de stock
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ProductVariant v SET v.currentStock = v.currentStock + :delta WHERE v.id = :variantId")
    void incrementCurrentStock(@Param("variantId") Long variantId, @Param("delta") int delta);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ProductVariant v SET v.reservedStock = v.reservedStock + :delta WHERE v.id = :variantId")
    void incrementReservedStock(@Param("variantId") Long variantId, @Param("delta") int delta);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ProductVariant v SET v.costPrice = :costPrice WHERE v.id = :variantId")
    void updateCostPrice(@Param("variantId") Long variantId, @Param("costPrice") java.math.BigDecimal costPrice);

    /** JOIN FETCH ensures the Product proxy is loaded within the same query — safe to use outside a transaction. */
    @Query("SELECT v FROM ProductVariant v JOIN FETCH v.product WHERE v.variantSku = :sku")
    Optional<ProductVariant> findByVariantSku(@Param("sku") String sku);

    @Query("SELECT v FROM ProductVariant v JOIN FETCH v.product WHERE LOWER(v.variantSku) = LOWER(:sku)")
    Optional<ProductVariant> findByVariantSkuIgnoreCase(@Param("sku") String sku);

    /** Trouve la première variante d'un produit par taille (case-insensitive). */
    java.util.Optional<ProductVariant> findFirstByProductIdAndSizeIgnoreCase(Long productId, String size);

    /** Returns distinct products that have at least one active variant with stock <= product.minThreshold and alertEnabled. */
    @Query("SELECT DISTINCT v.product FROM ProductVariant v WHERE v.active = true AND v.product.alertEnabled = true AND v.currentStock <= v.product.minThreshold")
    List<Product> findProductsWithLowStockVariants();
}
