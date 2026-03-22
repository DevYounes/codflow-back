package com.codflow.backend.delivery.provider.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
public class PickupRequest {
    private List<String> trackingNumbers;
    private LocalDate pickupDate;
    private String pickupAddress;
    private String contactName;
    private String contactPhone;
    private String notes;
}
