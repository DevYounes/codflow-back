package com.codflow.backend.charges.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class DailyChargesDto {
    private String date;
    private BigDecimal totalCharges;
    private BigDecimal deliveryCharges;
    private BigDecimal returnCharges;
    private BigDecimal refusedCharges;
    private BigDecimal cancellationCharges;
    private long shipmentCount;
}
