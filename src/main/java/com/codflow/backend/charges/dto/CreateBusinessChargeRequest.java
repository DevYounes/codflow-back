package com.codflow.backend.charges.dto;

import com.codflow.backend.charges.enums.BusinessChargeType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class CreateBusinessChargeRequest {

    @NotNull(message = "Le type de charge est obligatoire")
    private BusinessChargeType type;

    @NotBlank(message = "Le libellé est obligatoire")
    private String label;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false, message = "Le montant doit être > 0")
    private BigDecimal amount;

    @NotNull(message = "La date est obligatoire")
    private LocalDate chargeDate;

    private String notes;
}
