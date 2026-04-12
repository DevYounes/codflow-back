package com.codflow.backend.salary.dto;

import com.codflow.backend.salary.enums.CommissionType;
import com.codflow.backend.salary.enums.SalaryPaymentStatus;
import com.codflow.backend.salary.enums.SalaryType;
import com.codflow.backend.supplier.enums.PaymentMethod;
import com.codflow.backend.team.enums.Role;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class SalaryPaymentDto {
    private Long id;
    private Long userId;
    private String userFullName;
    private Role userRole;

    private LocalDate periodStart;
    private LocalDate periodEnd;

    private SalaryType salaryType;
    private String salaryTypeLabel;
    private CommissionType commissionType;
    private String commissionTypeLabel;

    private BigDecimal fixedSalary;
    private BigDecimal commissionPerConfirmed;
    private BigDecimal commissionPerDelivered;

    private int confirmedCount;
    private int deliveredCount;

    private BigDecimal commissionAmount;
    private BigDecimal bonus;
    private BigDecimal deduction;
    private BigDecimal totalAmount;

    private SalaryPaymentStatus status;
    private String statusLabel;

    private LocalDate paymentDate;
    private PaymentMethod paymentMethod;
    private String paymentMethodLabel;
    private String reference;
    private String notes;

    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
