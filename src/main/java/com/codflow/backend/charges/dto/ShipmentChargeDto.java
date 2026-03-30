package com.codflow.backend.charges.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class ShipmentChargeDto {
    private Long shipmentId;
    private String orderNumber;
    private String customerName;
    private String trackingNumber;
    private String shipmentStatus;
    private BigDecimal deliveryFee;
    private BigDecimal returnFee;
    private BigDecimal appliedFee;
    private String appliedFeeType;
    private LocalDateTime finalizedAt; // deliveredAt ou returnedAt
    private LocalDateTime createdAt;
}
