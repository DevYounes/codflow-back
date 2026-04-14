package com.codflow.backend.salary.dto;

import com.codflow.backend.salary.enums.CommissionType;
import com.codflow.backend.salary.enums.SalaryType;
import com.codflow.backend.team.enums.Role;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class SalaryConfigDto {
    private Long userId;
    private String fullName;
    private Role role;
    private SalaryType salaryType;
    private String salaryTypeLabel;
    private BigDecimal fixedSalary;
    private CommissionType commissionType;
    private String commissionTypeLabel;
    private BigDecimal commissionPerConfirmed;
    private BigDecimal commissionPerDelivered;
}
