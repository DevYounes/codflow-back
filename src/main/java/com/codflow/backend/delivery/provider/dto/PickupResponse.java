package com.codflow.backend.delivery.provider.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PickupResponse {
    private boolean success;
    private String pickupId;
    private String scheduledDate;
    private String message;
}
