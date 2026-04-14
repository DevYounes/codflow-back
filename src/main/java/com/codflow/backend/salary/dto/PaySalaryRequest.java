package com.codflow.backend.salary.dto;

import com.codflow.backend.supplier.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class PaySalaryRequest {

    @NotNull(message = "La date de paiement est obligatoire")
    private LocalDate paymentDate;

    @NotNull(message = "Le mode de paiement est obligatoire")
    private PaymentMethod paymentMethod;

    private String reference;

    private String notes;
}
