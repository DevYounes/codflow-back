package com.codflow.backend.salary.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.salary.dto.CreateSalaryPaymentRequest;
import com.codflow.backend.salary.dto.PaySalaryRequest;
import com.codflow.backend.salary.dto.SalaryConfigDto;
import com.codflow.backend.salary.dto.SalaryPaymentDto;
import com.codflow.backend.salary.dto.SalaryPreviewDto;
import com.codflow.backend.salary.dto.UpdateSalaryConfigRequest;
import com.codflow.backend.salary.dto.UpdateSalaryPaymentRequest;
import com.codflow.backend.salary.enums.SalaryPaymentStatus;
import com.codflow.backend.salary.service.SalaryService;
import com.codflow.backend.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/salaries")
@RequiredArgsConstructor
@Tag(name = "Salaries", description = "Gestion des salaires — configuration et fiches de paie")
public class SalaryController {

    private final SalaryService salaryService;

    // -------------------------------------------------------------------------
    // Configuration salariale
    // -------------------------------------------------------------------------

    @GetMapping("/configs")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lister les configurations salariales des utilisateurs actifs")
    public ResponseEntity<ApiResponse<List<SalaryConfigDto>>> listConfigs() {
        return ResponseEntity.ok(ApiResponse.success(salaryService.listConfigs()));
    }

    @GetMapping("/configs/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Obtenir la configuration salariale d'un utilisateur")
    public ResponseEntity<ApiResponse<SalaryConfigDto>> getConfig(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success(salaryService.getConfig(userId)));
    }

    @PutMapping("/configs/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mettre à jour la configuration salariale d'un utilisateur")
    public ResponseEntity<ApiResponse<SalaryConfigDto>> updateConfig(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateSalaryConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Configuration salariale mise à jour",
                salaryService.updateConfig(userId, request)));
    }

    // -------------------------------------------------------------------------
    // Aperçu de salaire (calcul sans persistance)
    // -------------------------------------------------------------------------

    @GetMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Calculer un aperçu de salaire pour une période donnée")
    public ResponseEntity<ApiResponse<SalaryPreviewDto>> preview(
            @RequestParam Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(salaryService.preview(userId, from, to)));
    }

    // -------------------------------------------------------------------------
    // Fiches de paie
    // -------------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Générer une fiche de paie pour un utilisateur")
    public ResponseEntity<ApiResponse<SalaryPaymentDto>> create(
            @Valid @RequestBody CreateSalaryPaymentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Fiche de paie créée",
                salaryService.create(request, principal)));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lister les fiches de paie")
    public ResponseEntity<ApiResponse<PageResponse<SalaryPaymentDto>>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) SalaryPaymentStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by("periodStart").descending().and(Sort.by("id").descending()));
        return ResponseEntity.ok(ApiResponse.success(
                salaryService.list(userId, status, from, to, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Obtenir une fiche de paie")
    public ResponseEntity<ApiResponse<SalaryPaymentDto>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(salaryService.get(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mettre à jour une fiche en brouillon (prime / déduction / notes)")
    public ResponseEntity<ApiResponse<SalaryPaymentDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSalaryPaymentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Fiche mise à jour",
                salaryService.update(id, request)));
    }

    @PatchMapping("/{id}/pay")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Marquer une fiche de paie comme payée")
    public ResponseEntity<ApiResponse<SalaryPaymentDto>> pay(
            @PathVariable Long id,
            @Valid @RequestBody PaySalaryRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Fiche marquée comme payée",
                salaryService.markAsPaid(id, request)));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Annuler une fiche (non payée)")
    public ResponseEntity<ApiResponse<SalaryPaymentDto>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "Fiche annulée",
                salaryService.cancel(id)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Supprimer une fiche de paie (brouillon ou annulée)")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        salaryService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Fiche supprimée"));
    }
}
