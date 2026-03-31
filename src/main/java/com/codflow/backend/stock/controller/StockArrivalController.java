package com.codflow.backend.stock.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.security.UserPrincipal;
import com.codflow.backend.stock.dto.CreateStockArrivalRequest;
import com.codflow.backend.stock.dto.StockArrivalDto;
import com.codflow.backend.stock.service.StockArrivalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stock/arrivals")
@RequiredArgsConstructor
@Tag(name = "Arrivages", description = "Gestion des arrivages de stock")
public class StockArrivalController {

    private final StockArrivalService arrivalService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(
        summary = "Créer un arrivage",
        description = """
            Enregistre un nouvel arrivage de stock pour un produit.
            Pour un produit avec variantes (ex: chaussures), spécifier variantId + quantity par ligne.
            Pour un produit sans variantes, laisser variantId null.
            Met à jour automatiquement le stock produit et variante.
            """
    )
    public ResponseEntity<ApiResponse<StockArrivalDto>> createArrival(
            @Valid @RequestBody CreateStockArrivalRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Arrivage enregistré", arrivalService.createArrival(request, principal)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Lister tous les arrivages (paginé)")
    public ResponseEntity<ApiResponse<Page<StockArrivalDto>>> listArrivals(
            @RequestParam(defaultValue = "0")       int page,
            @RequestParam(defaultValue = "20")      int size,
            @RequestParam(defaultValue = "arrivedAt") String sortBy,
            @RequestParam(defaultValue = "desc")    String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        return ResponseEntity.ok(ApiResponse.success(arrivalService.listArrivals(PageRequest.of(page, size, sort))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Détail d'un arrivage")
    public ResponseEntity<ApiResponse<StockArrivalDto>> getArrival(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(arrivalService.getArrival(id)));
    }

    @GetMapping("/product/{productId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Historique des arrivages pour un produit")
    public ResponseEntity<ApiResponse<List<StockArrivalDto>>> listByProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.success(arrivalService.listByProduct(productId)));
    }
}
