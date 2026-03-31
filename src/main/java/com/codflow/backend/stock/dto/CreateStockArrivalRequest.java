package com.codflow.backend.stock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class CreateStockArrivalRequest {

    @NotBlank(message = "La référence est obligatoire")
    private String reference;

    @NotNull(message = "Le produit est obligatoire")
    private Long productId;

    @NotNull(message = "La date d'arrivage est obligatoire")
    private LocalDate arrivedAt;

    private String notes;

    @NotEmpty(message = "Au moins une ligne d'arrivage est requise")
    @Valid
    private List<ArrivalItemRequest> items;

    @Getter
    @Setter
    public static class ArrivalItemRequest {
        /** Null si produit sans variantes */
        private Long variantId;

        @NotNull
        private Integer quantity;

        /** Override du coût unitaire pour cet arrivage */
        private BigDecimal unitCost;
    }
}
