package com.codflow.backend.analytics.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class KpiSummaryDto {

    // Volume
    private long totalOrders;
    private long todayOrders;
    private long weekOrders;
    private long monthOrders;

    // Confirmation
    private long confirmedOrders;
    private long cancelledOrders;
    private long pendingOrders;
    private double confirmationRate;   // %
    private double cancellationRate;   // %

    // Delivery
    private long deliveredOrders;
    private long inDeliveryOrders;
    private long returnedOrders;
    private double deliverySuccessRate; // %
    private double returnRate;          // %

    // Revenue
    private BigDecimal totalRevenue;
    private BigDecimal confirmedRevenue;
    private BigDecimal averageOrderValue;

    // Stock
    private long lowStockProducts;
    private long outOfStockProducts;
    private long activeAlerts;

    // Breakdown by status
    private Map<String, Long> ordersByStatus;

    // Breakdown by source
    private Map<String, Long> ordersBySource;

    // Daily trend (last 7 days)
    private List<DailyStatsDto> dailyTrend;
}
