package com.codflow.backend.delivery.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CreateDeliveryNoteRequest {

    @NotNull(message = "L'ID du transporteur est requis")
    private Long providerId;

    /** IDs des DeliveryShipments à inclure dans ce bon de livraison */
    @NotEmpty(message = "La liste des envois est requise")
    private List<Long> shipmentIds;

    private String notes;
}
