package com.codflow.backend.delivery.dto;

import com.codflow.backend.delivery.enums.ShipmentStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Colis expédié dont le retour physique n'a pas encore été confirmé.
 * Utilisé pour le tableau de bord "Retours en attente".
 */
@Getter
@Builder
public class PendingReturnDto {

    private Long shipmentId;
    private String trackingNumber;
    private Long orderId;
    private String orderNumber;
    private String customerName;
    private String customerPhone;
    private String city;

    /** Statut actuel côté transporteur */
    private ShipmentStatus status;
    private String statusLabel;

    /** Libellé brut retourné par le transporteur (ex: "Colis Refusé") */
    private String providerStatusLabel;

    /**
     * Date à laquelle le colis est entré dans l'état de retour :
     * - returnedAt si le transporteur a marqué RETURNED
     * - updatedAt sinon (FAILED_DELIVERY / CANCELLED)
     */
    private LocalDateTime statusChangedAt;

    /** Nombre de jours depuis que le colis est en attente de retour */
    private long daysPending;

    /**
     * True si > 7 jours sans confirmation de retour physique.
     * Indique un risque de perte du colis par le transporteur.
     */
    private boolean overdue;

    private BigDecimal appliedFee;
    private String appliedFeeType;
}
