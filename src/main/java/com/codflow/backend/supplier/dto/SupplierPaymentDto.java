package com.codflow.backend.supplier.dto;

import com.codflow.backend.supplier.enums.PaymentMethod;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class SupplierPaymentDto {
    private Long id;
    private BigDecimal amount;
    private LocalDate paymentDate;
    private PaymentMethod paymentMethod;
    private String paymentMethodLabel;
    private String reference;
    private String notes;
    private LocalDateTime createdAt;
}
