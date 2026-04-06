package com.codflow.backend.product.entity;

import com.codflow.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "product_variants",
        uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "color", "size"}))
public class ProductVariant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(length = 100)
    private String color;

    @Column(length = 50)
    private String size;

    /**
     * Variant-specific SKU (e.g. SHOE-RED-42). If null, falls back to product SKU.
     */
    @Column(name = "variant_sku", length = 100)
    private String variantSku;

    /**
     * Price override. If null, inherits product base price.
     */
    @Column(name = "price_override", precision = 10, scale = 2)
    private BigDecimal priceOverride;

    /**
     * Coût de revient override. If null, inherits product costPrice.
     */
    @Column(name = "cost_price", precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "current_stock", nullable = false)
    private int currentStock = 0;

    /** Stock réservé par des commandes confirmées non encore livrées. */
    @Column(name = "reserved_stock", nullable = false)
    private int reservedStock = 0;

    @Column(nullable = false)
    private boolean active = true;

    /** Stock disponible = currentStock - reservedStock. */
    public int getAvailableStock() {
        return Math.max(0, currentStock - reservedStock);
    }
}
