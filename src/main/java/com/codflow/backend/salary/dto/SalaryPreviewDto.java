package com.codflow.backend.salary.dto;

import com.codflow.backend.salary.enums.CommissionType;
import com.codflow.backend.salary.enums.SalaryType;
import com.codflow.backend.team.enums.Role;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Aperçu du salaire calculé pour un utilisateur sur une période donnée.
 * Ne persiste rien — sert d'aperçu avant création d'un {@code SalaryPayment}.
 */
@Getter
@Builder
public class SalaryPreviewDto {
    private Long userId;
    private String fullName;
    private Role role;
    private LocalDate periodStart;
    private LocalDate periodEnd;

    private SalaryType salaryType;
    private String salaryTypeLabel;
    private CommissionType commissionType;
    private String commissionTypeLabel;

    private BigDecimal fixedSalary;
    private BigDecimal commissionPerConfirmed;
    private BigDecimal commissionPerDelivered;

    private long confirmedCount;
    private long deliveredCount;

    private BigDecimal commissionFromConfirmed;
    private BigDecimal commissionFromDelivered;
    private BigDecimal commissionAmount;

    private BigDecimal totalAmount;
}
