package com.codflow.backend.analytics.controller;

import com.codflow.backend.analytics.dto.*;
import com.codflow.backend.analytics.service.AnalyticsService;
import com.codflow.backend.charges.dto.BusinessProfitSummaryDto;
import com.codflow.backend.charges.dto.DailyChargesDto;
import com.codflow.backend.charges.dto.DeliveryChargesSummaryDto;
import com.codflow.backend.charges.service.BusinessChargesService;
import com.codflow.backend.charges.service.ChargesService;
import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "KPIs et statistiques")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final ChargesService chargesService;
    private final BusinessChargesService businessChargesService;

    @GetMapping("/my-dashboard")
    @PreAuthorize("hasRole('AGENT')")
    @Operation(summary = "Tableau de bord personnel de l'agent connecté")
    public ResponseEntity<ApiResponse<AgentDashboardDto>> getMyDashboard(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getAgentDashboard(principal)));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "KPIs globaux - Tableau de bord principal")
    public ResponseEntity<ApiResponse<KpiSummaryDto>> getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // Si un seul paramètre est fourni, on complète avec une borne implicite.
        // from sans to → jusqu'à la fin d'aujourd'hui
        // to sans from → depuis le début des temps (no lower bound → null)
        LocalDateTime fromDt = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDt   = from != null || to != null
                ? (to != null ? to.plusDays(1).atStartOfDay() : LocalDateTime.now())
                : null;
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getSummary(fromDt, toDt)));
    }

    @GetMapping("/agents")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Performance des agents de confirmation")
    public ResponseEntity<ApiResponse<List<AgentPerformanceDto>>> getAgentPerformance() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getAgentPerformance()));
    }

    @GetMapping("/delivery")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Statistiques de livraison")
    public ResponseEntity<ApiResponse<DeliveryStatsDto>> getDeliveryStats() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getDeliveryStats()));
    }

    @GetMapping("/daily-trend")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Tendance journalière des commandes")
    public ResponseEntity<ApiResponse<List<DailyStatsDto>>> getDailyTrend(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getDailyTrend(days)));
    }

    @GetMapping("/charges")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Résumé des charges de livraison")
    public ResponseEntity<ApiResponse<DeliveryChargesSummaryDto>> getCharges(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(chargesService.getDeliverySummary(from, to)));
    }

    @GetMapping("/charges/daily")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Charges de livraison journalières")
    public ResponseEntity<ApiResponse<List<DailyChargesDto>>> getChargesDaily(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(chargesService.getChargesDaily(days)));
    }

    @GetMapping("/profit")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Récapitulatif complet du profit COD (CA, marges, charges opé, gain net)")
    public ResponseEntity<ApiResponse<BusinessProfitSummaryDto>> getProfit(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(businessChargesService.getProfitSummary(from, to)));
    }

    @GetMapping("/products")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(
        summary = "Performance par produit",
        description = """
            Agrégation des ventes par produit : quantités commandées/livrées/retournées/annulées,
            CA, coût produits, marge brute, taux de livraison, panier moyen.
            Filtre optionnel par période (from / to).
            Seuls les produits liés aux articles de commande sont inclus.
            """
    )
    public ResponseEntity<ApiResponse<List<ProductPerformanceDto>>> getProductPerformance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getProductPerformance(from, to)));
    }
}
