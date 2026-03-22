package com.codflow.backend.stock.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.security.UserPrincipal;
import com.codflow.backend.stock.dto.AdjustStockRequest;
import com.codflow.backend.stock.dto.StockAlertDto;
import com.codflow.backend.stock.dto.StockMovementDto;
import com.codflow.backend.stock.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stock")
@RequiredArgsConstructor
@Tag(name = "Stock", description = "Gestion du stock")
public class StockController {

    private final StockService stockService;

    @PostMapping("/adjust")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Ajuster le stock d'un produit")
    public ResponseEntity<ApiResponse<StockMovementDto>> adjustStock(
            @Valid @RequestBody AdjustStockRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Stock ajusté avec succès", stockService.adjustStock(request, principal)));
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Historique des mouvements de stock")
    public ResponseEntity<ApiResponse<PageResponse<StockMovementDto>>> getMovements(
            @RequestParam(required = false) Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.success(stockService.getMovements(productId, pageable)));
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Obtenir les alertes de stock actives")
    public ResponseEntity<ApiResponse<List<StockAlertDto>>> getActiveAlerts() {
        return ResponseEntity.ok(ApiResponse.success(stockService.getActiveAlerts()));
    }

    @PutMapping("/alerts/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Résoudre une alerte de stock")
    public ResponseEntity<ApiResponse<Void>> resolveAlert(@PathVariable Long id,
                                                           @AuthenticationPrincipal UserPrincipal principal) {
        stockService.resolveAlert(id, principal);
        return ResponseEntity.ok(ApiResponse.success("Alerte résolue"));
    }
}
