package com.codflow.backend.stock.dto;

import com.codflow.backend.stock.enums.MovementType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdjustStockRequest {

    @NotNull
    private Long productId;

    /** Optionnel — si renseigné, met à jour le stock de cette variante spécifique */
    private Long variantId;

    @NotNull
    private MovementType movementType;

    @NotNull
    @Min(1)
    private Integer quantity;

    private String reason;
}
