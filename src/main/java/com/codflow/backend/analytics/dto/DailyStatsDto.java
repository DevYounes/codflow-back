package com.codflow.backend.analytics.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class DailyStatsDto {
    private String date;
    private long totalOrders;
    private long confirmedOrders;
    private long cancelledOrders;
    private double confirmationRate;
    private BigDecimal revenue;
}
