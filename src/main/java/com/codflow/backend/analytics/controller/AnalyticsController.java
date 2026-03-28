package com.codflow.backend.analytics.controller;

import com.codflow.backend.analytics.dto.*;
import com.codflow.backend.analytics.service.AnalyticsService;
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
        LocalDateTime fromDt = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDt   = to   != null ? to.plusDays(1).atStartOfDay() : null;
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
}
