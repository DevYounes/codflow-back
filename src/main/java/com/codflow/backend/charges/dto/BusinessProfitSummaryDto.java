package com.codflow.backend.charges.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Récapitulatif complet du profit pour un business COD sur une période donnée.
 *
 * Formule :
 *   Marge brute   = CA - coût produits - frais livreur
 *   Gain net      = Marge brute - charges opérationnelles (pub + loyer + salaires + ...)
 */
@Getter
@Builder
public class BusinessProfitSummaryDto {

    private LocalDate from;
    private LocalDate to;

    // -----------------------------------------------------------------------
    // Volumes commandes
    // -----------------------------------------------------------------------
    private long totalOrders;      // toutes commandes de la période (par date création)
    private long deliveredOrders;  // livrées (base du CA)
    private long returnedOrders;
    private long cancelledOrders;
    private double deliveryRate;   // % livraison = delivered / total

    // -----------------------------------------------------------------------
    // Chiffre d'affaires & marges
    // -----------------------------------------------------------------------
    private BigDecimal revenue;           // CA = somme montants COD livrés
    private BigDecimal productCosts;      // Coût de revient des produits livrés
    private BigDecimal deliveryCharges;   // Frais livreur (Ozon) : livraison + retour + refus
    private BigDecimal grossMargin;       // Marge brute = revenue - productCosts - deliveryCharges
    private BigDecimal grossMarginRate;   // % marge brute = grossMargin / revenue * 100

    // -----------------------------------------------------------------------
    // Charges opérationnelles (saisies manuellement)
    // -----------------------------------------------------------------------
    private BigDecimal pubCosts;           // PUBLICITE
    private BigDecimal salaryCosts;        // SALAIRE
    private BigDecimal rentCosts;          // LOYER
    private BigDecimal utilitiesCosts;     // EAU + ELECTRICITE + INTERNET
    private BigDecimal otherCosts;         // TRANSPORT + EMBALLAGE + AUTRE
    private BigDecimal totalOperationalCosts; // somme de toutes les charges opé

    // -----------------------------------------------------------------------
    // Gain net
    // -----------------------------------------------------------------------
    private BigDecimal netProfit;          // grossMargin - totalOperationalCosts
    private BigDecimal netMarginRate;      // % marge nette = netProfit / revenue * 100

    // -----------------------------------------------------------------------
    // Métriques pub COD (clés pour optimiser les campagnes)
    // -----------------------------------------------------------------------
    private BigDecimal costPerLead;        // pubCosts / totalOrders
    private BigDecimal costPerDelivery;    // pubCosts / deliveredOrders
    private BigDecimal revenuePerDelivery; // revenue / deliveredOrders (panier moyen livré)
}
