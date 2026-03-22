package com.codflow.backend.analytics.controller;

import com.codflow.backend.analytics.dto.*;
import com.codflow.backend.analytics.service.AnalyticsService;
import com.codflow.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
@Tag(name = "Analytics", description = "KPIs et statistiques")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/summary")
    @Operation(summary = "KPIs globaux - Tableau de bord principal")
    public ResponseEntity<ApiResponse<KpiSummaryDto>> getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getSummary(from, to)));
    }

    @GetMapping("/agents")
    @Operation(summary = "Performance des agents de confirmation")
    public ResponseEntity<ApiResponse<List<AgentPerformanceDto>>> getAgentPerformance() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getAgentPerformance()));
    }

    @GetMapping("/delivery")
    @Operation(summary = "Statistiques de livraison")
    public ResponseEntity<ApiResponse<DeliveryStatsDto>> getDeliveryStats() {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getDeliveryStats()));
    }

    @GetMapping("/daily-trend")
    @Operation(summary = "Tendance journalière des commandes")
    public ResponseEntity<ApiResponse<List<DailyStatsDto>>> getDailyTrend(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getDailyTrend(days)));
    }
}
