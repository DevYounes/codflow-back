package com.codflow.backend.stock.entity;

import com.codflow.backend.product.entity.ProductVariant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "stock_arrival_items")
@EntityListeners(AuditingEntityListener.class)
public class StockArrivalItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "arrival_id", nullable = false)
    private StockArrival arrival;

    /** Null si le produit n'a pas de variantes */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(nullable = false)
    private int quantity;

    /** Coût unitaire à ce réapprovisionnement (optionnel — override du cost_price produit) */
    @Column(name = "unit_cost", precision = 10, scale = 2)
    private BigDecimal unitCost;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
