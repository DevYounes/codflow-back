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
@Table(name = "products")
public class Product extends BaseEntity {

    @Column(unique = true, nullable = false, length = 100)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    /** Coût de revient (prix d'achat) */
    @Column(name = "cost_price", precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "current_stock", nullable = false)
    private int currentStock = 0;

    @Column(name = "min_threshold", nullable = false)
    private int minThreshold = 10;

    @Column(name = "alert_enabled", nullable = false)
    private boolean alertEnabled = true;
}
