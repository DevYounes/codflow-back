package com.codflow.backend.delivery.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateShipmentRequest {

    @NotNull(message = "La commande est obligatoire")
    private Long orderId;

    @NotNull(message = "Le transporteur est obligatoire")
    private Long providerId;

    private String notes;
}
