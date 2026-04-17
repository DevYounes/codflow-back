package com.codflow.backend.stock.dto;

import com.codflow.backend.stock.enums.MovementType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdjustStockRequest {

    @NotNull
    private Long productId;

    /**
     * Optionnel. Si renseigné, l'ajustement porte sur cette variante.
     * Si null, l'ajustement porte directement sur Product.currentStock
     * (cas typique : consommables sans variantes).
     */
    private Long variantId;

    /**
     * Optionnel. Deux modes acceptés :
     *  - {movementType=IN/OUT/RETURN/ADJUSTMENT, quantity > 0} → mode explicite
     *  - {movementType=null, quantity signé} → mode raccourci :
     *      quantity > 0 → IN ; quantity < 0 → OUT (la valeur absolue est utilisée)
     */
    private MovementType movementType;

    @NotNull
    private Integer quantity;

    private String reason;
}
