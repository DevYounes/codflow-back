package com.codflow.backend.supplier.entity;

import com.codflow.backend.common.entity.BaseEntity;
import com.codflow.backend.supplier.enums.SupplierOrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "supplier_orders")
public class SupplierOrder extends BaseEntity {

    @Column(name = "order_number", unique = true, nullable = false, length = 100)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SupplierOrderStatus status = SupplierOrderStatus.BROUILLON;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** Somme des paiements enregistrés. */
    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "supplierOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SupplierOrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "supplierOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("paymentDate ASC")
    private List<SupplierPayment> payments = new ArrayList<>();

    @OneToMany(mappedBy = "supplierOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("deliveryDate ASC")
    private List<SupplierDelivery> deliveries = new ArrayList<>();

    /** Montant restant à payer = totalAmount - paidAmount. */
    public BigDecimal getRemainingAmount() {
        return totalAmount.subtract(paidAmount);
    }

    public void recalculateTotalAmount() {
        this.totalAmount = items.stream()
                .map(SupplierOrderItem::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
