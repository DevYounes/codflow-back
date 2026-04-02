package com.codflow.backend.charges.dto;

import com.codflow.backend.charges.enums.BusinessChargeType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class BusinessChargeDto {
    private Long id;
    private BusinessChargeType type;
    private String typeLabel;
    private String label;
    private BigDecimal amount;
    private LocalDate chargeDate;
    private String notes;
    private String createdByName;
    private LocalDateTime createdAt;
}
