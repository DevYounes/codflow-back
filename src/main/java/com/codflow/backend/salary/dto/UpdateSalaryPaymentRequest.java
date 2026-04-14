package com.codflow.backend.salary.dto;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class UpdateSalaryPaymentRequest {

    @PositiveOrZero(message = "La prime doit être positive ou nulle")
    private BigDecimal bonus;

    @PositiveOrZero(message = "La déduction doit être positive ou nulle")
    private BigDecimal deduction;

    private String notes;
}
