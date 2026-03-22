package com.codflow.backend.analytics.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class DeliveryStatsDto {
    private long totalShipments;
    private long delivered;
    private long inTransit;
    private long returned;
    private long failed;
    private double deliveryRate;
    private double returnRate;
    private Map<String, Long> shipmentsByProvider;
    private Map<String, Long> shipmentsByStatus;
}
