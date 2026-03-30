package com.codflow.backend.charges.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class DeliveryChargesSummaryDto {

    private LocalDate from;
    private LocalDate to;

    // Volumes
    private long totalShipments;
    private long delivered;
    private long returned;
    private long cancelled;
    private long pending;       // pas encore finalisés

    // Charges
    private BigDecimal totalCharges;        // somme de tous les applied_fee
    private BigDecimal deliveryCharges;     // frais pour les colis livrés
    private BigDecimal returnCharges;       // frais pour les colis retournés
    private BigDecimal cancellationCharges; // frais d'annulation

    // Moyennes
    private BigDecimal avgDeliveryFee;
    private BigDecimal avgReturnFee;
}
