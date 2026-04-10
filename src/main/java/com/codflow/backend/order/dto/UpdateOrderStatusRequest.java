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

    /**
     * Quand true, les opérations stock liées au statut RETOURNE sont différées.
     * Utilisé par DeliveryService : le stock est libéré à la confirmation physique
     * du retour (confirmReturnReceived) et non au moment où Ozon dit "Retourné".
     * Ne jamais envoyer ce flag depuis le frontend — usage interne uniquement.
     */
    private boolean deferReturnStock = false;
}
