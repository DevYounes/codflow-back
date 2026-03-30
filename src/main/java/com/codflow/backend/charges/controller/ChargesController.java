package com.codflow.backend.charges.controller;

import com.codflow.backend.charges.dto.DeliveryChargesSummaryDto;
import com.codflow.backend.charges.dto.ShipmentChargeDto;
import com.codflow.backend.charges.service.ChargesService;
import com.codflow.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/charges")
@RequiredArgsConstructor
@Tag(name = "Charges", description = "Calcul des charges du business (livraison, retour...)")
public class ChargesController {

    private final ChargesService chargesService;

    @GetMapping("/delivery/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(
        summary = "Résumé des charges de livraison",
        description = """
            Retourne le total des frais de livraison par type (livré / retourné / annulé)
            sur une période donnée. Les tarifs sont ceux snapshot au moment de la création
            du colis depuis l'API Ozon Express (PRICE = livraison, RETOUR = retour/refus).
            """
    )
    public ResponseEntity<ApiResponse<DeliveryChargesSummaryDto>> getDeliverySummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
                chargesService.getDeliverySummary(from, to)));
    }

    @GetMapping("/delivery/details")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(
        summary = "Détail des frais par envoi",
        description = "Liste paginée des frais appliqués par envoi. Filtrable par type: LIVRAISON, RETOUR, ANNULATION."
    )
    public ResponseEntity<ApiResponse<Page<ShipmentChargeDto>>> getDeliveryDetails(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String feeType,
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "20")   int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        return ResponseEntity.ok(ApiResponse.success(
                chargesService.getDeliveryDetails(from, to, feeType,
                        PageRequest.of(page, size, sort))));
    }
}
