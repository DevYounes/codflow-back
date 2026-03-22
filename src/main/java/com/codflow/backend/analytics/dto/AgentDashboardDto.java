package com.codflow.backend.analytics.dto;

import lombok.Builder;
import lombok.Getter;

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
    private long confirmedOrders;
    private long cancelledOrders;   // ANNULE + PAS_SERIEUX + FAKE_ORDER + DOUBLON
    private long pendingOrders;     // tout ce qui n'est ni confirmé ni annulé
    private long potentialDuplicates;

    // Taux (%)
    private double confirmationRate;
    private double cancellationRate;

    // Tendance 7 jours (ses commandes uniquement)
    private List<DailyStatsDto> dailyTrend;
}
