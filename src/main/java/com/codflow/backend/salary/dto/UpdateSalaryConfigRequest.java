package com.codflow.backend.salary.dto;

import com.codflow.backend.salary.enums.CommissionType;
import com.codflow.backend.salary.enums.SalaryType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class UpdateSalaryConfigRequest {

    @NotNull(message = "Le type de salaire est obligatoire")
    private SalaryType salaryType;

    @PositiveOrZero(message = "Le salaire fixe doit être positif ou nul")
    private BigDecimal fixedSalary;

    private CommissionType commissionType;

    @PositiveOrZero(message = "La commission par confirmé doit être positive ou nulle")
    private BigDecimal commissionPerConfirmed;

    @PositiveOrZero(message = "La commission par livré doit être positive ou nulle")
    private BigDecimal commissionPerDelivered;
}
