package com.codflow.backend.order.entity;

import com.codflow.backend.product.entity.Product;
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
@Table(name = "order_items")
@EntityListeners(AuditingEntityListener.class)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "product_sku", length = 100)
    private String productSku;

    @Column(name = "variant_color", length = 100)
    private String variantColor;

    @Column(name = "variant_size", length = 50)
    private String variantSize;

    @Column(nullable = false)
    private int quantity = 1;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "total_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice = BigDecimal.ZERO;

    /** Snapshot du coût de revient au moment de la confirmation */
    @Column(name = "unit_cost", precision = 10, scale = 2)
    private BigDecimal unitCost;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public void calculateTotalPrice() {
        this.totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
