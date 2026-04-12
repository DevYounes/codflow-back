package com.codflow.backend.supplier.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class RecordDeliveryRequest {

    private String lotNumber;

    @NotNull
    private LocalDate deliveryDate;

    private String notes;

    @NotEmpty
    @Valid
    private List<DeliveryItemRequest> items;

    @Getter
    @Setter
    public static class DeliveryItemRequest {
        /** ID de la ligne SupplierOrderItem concernée. */
        @NotNull
        private Long orderItemId;

        @NotNull
        @Positive
        private Integer quantityReceived;

        /**
         * Coût unitaire à la réception (optionnel — si absent, utilise le coût de commande).
         * Utilisé pour le calcul du CMUP.
         */
        private BigDecimal unitCost;
    }
}
