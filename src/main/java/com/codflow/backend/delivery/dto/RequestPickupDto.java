package com.codflow.backend.delivery.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class RequestPickupDto {

    @NotNull
    private Long providerId;

    private List<Long> shipmentIds; // Specific shipments to include; null = all pending

    private LocalDate pickupDate;

    private String notes;
}
