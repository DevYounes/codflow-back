package com.codflow.backend.supplier.dto;

import com.codflow.backend.supplier.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class RecordPaymentRequest {

    @NotNull
    @Positive
    private BigDecimal amount;

    @NotNull
    private LocalDate paymentDate;

    private PaymentMethod paymentMethod = PaymentMethod.ESPECES;

    private String reference;

    private String notes;
}
