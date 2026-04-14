package com.codflow.backend.salary.entity;

import com.codflow.backend.common.entity.BaseEntity;
import com.codflow.backend.salary.enums.CommissionType;
import com.codflow.backend.salary.enums.SalaryPaymentStatus;
import com.codflow.backend.salary.enums.SalaryType;
import com.codflow.backend.supplier.enums.PaymentMethod;
import com.codflow.backend.team.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "salary_payments")
public class SalaryPayment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    // Snapshot de la config salariale
    @Enumerated(EnumType.STRING)
    @Column(name = "salary_type", nullable = false, length = 30)
    private SalaryType salaryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "commission_type", length = 30)
    private CommissionType commissionType;

    @Column(name = "fixed_salary", nullable = false, precision = 12, scale = 2)
    private BigDecimal fixedSalary = BigDecimal.ZERO;

    @Column(name = "commission_per_confirmed", precision = 10, scale = 2)
    private BigDecimal commissionPerConfirmed;

    @Column(name = "commission_per_delivered", precision = 10, scale = 2)
    private BigDecimal commissionPerDelivered;

    // Compteurs retenus pour le calcul
    @Column(name = "confirmed_count", nullable = false)
    private int confirmedCount = 0;

    @Column(name = "delivered_count", nullable = false)
    private int deliveredCount = 0;

    // Montants
    @Column(name = "commission_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal bonus = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal deduction = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    // Statut et règlement
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SalaryPaymentStatus status = SalaryPaymentStatus.BROUILLON;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 30)
    private PaymentMethod paymentMethod;

    @Column(length = 100)
    private String reference;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;
}
