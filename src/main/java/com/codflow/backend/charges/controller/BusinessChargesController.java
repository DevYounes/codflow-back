package com.codflow.backend.charges.controller;

import com.codflow.backend.charges.dto.BusinessChargeDto;
import com.codflow.backend.charges.dto.BusinessProfitSummaryDto;
import com.codflow.backend.charges.dto.CreateBusinessChargeRequest;
import com.codflow.backend.charges.enums.BusinessChargeType;
import com.codflow.backend.charges.service.BusinessChargesService;
import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/charges/business")
@RequiredArgsConstructor
@Tag(name = "Business Charges", description = "Charges opérationnelles (pub, loyer, salaires...) et calcul du gain net")
public class BusinessChargesController {

    private final BusinessChargesService service;

    // -------------------------------------------------------------------------
    // Récapitulatif profit COD (endpoint principal)
    // -------------------------------------------------------------------------

    @GetMapping("/profit")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(
        summary = "Récapitulatif complet du profit COD",
        description = """
            Retourne une analyse complète du business sur la période :
            - CA (commandes livrées)
            - Coût de revient produits
            - Frais livreur (Ozon)
            - Marge brute
            - Charges opérationnelles (pub, loyer, salaires, factures...)
            - Gain net
            - Métriques pub COD : coût/lead, coût/livraison

            Par défaut : mois en cours.
            """
    )
    public ResponseEntity<ApiResponse<BusinessProfitSummaryDto>> getProfitSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(service.getProfitSummary(from, to)));
    }

    // -------------------------------------------------------------------------
    // CRUD charges opérationnelles
    // -------------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(
        summary = "Ajouter une charge opérationnelle",
        description = "Types disponibles : PUBLICITE, LOYER, SALAIRE, ELECTRICITE, EAU, INTERNET, TRANSPORT, EMBALLAGE, AUTRE"
    )
    public ResponseEntity<ApiResponse<BusinessChargeDto>> addCharge(
            @Valid @RequestBody CreateBusinessChargeRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Charge ajoutée", service.addCharge(request, principal)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Modifier une charge")
    public ResponseEntity<ApiResponse<BusinessChargeDto>> updateCharge(
            @PathVariable Long id,
            @Valid @RequestBody CreateBusinessChargeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Charge mise à jour", service.updateCharge(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Supprimer une charge")
    public ResponseEntity<ApiResponse<Void>> deleteCharge(@PathVariable Long id) {
        service.deleteCharge(id);
        return ResponseEntity.ok(ApiResponse.success("Charge supprimée"));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Lister les charges opérationnelles (paginé, filtrable par type et période)")
    public ResponseEntity<ApiResponse<Page<BusinessChargeDto>>> listCharges(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) BusinessChargeType type,
            @RequestParam(defaultValue = "0")           int page,
            @RequestParam(defaultValue = "20")          int size,
            @RequestParam(defaultValue = "chargeDate")  String sortBy,
            @RequestParam(defaultValue = "desc")        String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        return ResponseEntity.ok(ApiResponse.success(
                service.listCharges(from, to, type, PageRequest.of(page, size, sort))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Détail d'une charge")
    public ResponseEntity<ApiResponse<BusinessChargeDto>> getCharge(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getCharge(id)));
    }
}
