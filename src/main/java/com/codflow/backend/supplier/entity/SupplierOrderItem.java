package com.codflow.backend.supplier.entity;

import com.codflow.backend.common.entity.BaseEntity;
import com.codflow.backend.product.entity.Product;
import com.codflow.backend.product.entity.ProductVariant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "supplier_order_items")
public class SupplierOrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_order_id", nullable = false)
    private SupplierOrder supplierOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "product_sku", length = 100)
    private String productSku;

    @Column(name = "quantity_ordered", nullable = false)
    private int quantityOrdered;

    @Column(name = "quantity_received", nullable = false)
    private int quantityReceived = 0;

    @Column(name = "unit_cost", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "total_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalCost;

    public void calculateTotalCost() {
        this.totalCost = unitCost.multiply(BigDecimal.valueOf(quantityOrdered));
    }

    public int getRemainingQuantity() {
        return quantityOrdered - quantityReceived;
    }

    public boolean isFullyReceived() {
        return quantityReceived >= quantityOrdered;
    }
}
