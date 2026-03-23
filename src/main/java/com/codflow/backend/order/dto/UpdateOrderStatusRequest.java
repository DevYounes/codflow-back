package com.codflow.backend.order.dto;

import com.codflow.backend.order.enums.OrderStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateOrderStatusRequest {

    @NotNull(message = "Le statut est obligatoire")
    private OrderStatus status;

    private String notes;

    /**
     * Ozon Express city ID — required when status = CONFIRME.
     * The frontend fetches the list from GET /api/v1/ozon/cities.
     */
    private String deliveryCityId;
}
