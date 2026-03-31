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
    private BigDecimal deliveryCharges;     // frais livraison (DELIVERED-PRICE)
    private BigDecimal returnCharges;       // frais retour (RETURNED-PRICE, souvent 0)
    private BigDecimal refusedCharges;      // frais refus à la porte (REFUSED-PRICE)
    private BigDecimal cancellationCharges; // frais annulation

    // Moyennes
    private BigDecimal avgDeliveryFee;
    private BigDecimal avgReturnFee;

    // Coût des produits (sur commandes livrées)
    private BigDecimal productCosts;     // somme des coûts de revient des articles livrés
    private BigDecimal revenue;          // CA = somme des montants COD des commandes livrées
    private BigDecimal netProfit;        // Gain net = revenue - totalCharges - productCosts
}
