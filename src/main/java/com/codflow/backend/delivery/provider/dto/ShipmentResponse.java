package com.codflow.backend.delivery.provider.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ShipmentResponse {
    private boolean success;
    private String trackingNumber;
    private String providerOrderId;
    private String status;
    private String message;
    private String labelUrl;
    private String rawResponse;
}
