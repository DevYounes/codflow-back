package com.codflow.backend.analytics.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * KPIs personnels d'un agent de confirmation.
 */
@Getter
@Builder
public class AgentDashboardDto {

    // Identité
    private Long   agentId;
    private String agentName;

    // Volume global
    private long totalAssigned;
    private long todayOrders;
    private long weekOrders;
    private long monthOrders;

    // Résultats
    private long confirmedOrders;   // CONFIRME + toute la pipeline livraison
    private long deliveredOrders;   // LIVRE (basé sur retour société de livraison)
    private long cancelledOrders;   // ANNULE + PAS_SERIEUX + FAKE_ORDER
    private long doublonOrders;     // DOUBLON (traitement séparé, pas une annulation)
    private long pendingOrders;     // tout ce qui n'est ni confirmé, ni annulé, ni doublon
    private long potentialDuplicates;

    // Taux (%)
    private double confirmationRate;
    private double cancellationRate;

    // Chiffre d'affaires (commandes livrées)
    private BigDecimal revenue;

    // Tendance 7 jours (ses commandes uniquement)
    private List<DailyStatsDto> dailyTrend;
}
