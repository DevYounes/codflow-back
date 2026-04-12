package com.codflow.backend.supplier.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.supplier.dto.*;
import com.codflow.backend.supplier.enums.SupplierOrderStatus;
import com.codflow.backend.supplier.service.SupplierOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/supplier-orders")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
@Tag(name = "Supplier Orders", description = "Bons de commande fournisseurs — paiements et réceptions")
public class SupplierOrderController {

    private final SupplierOrderService supplierOrderService;

    @PostMapping
    @Operation(summary = "Créer un bon de commande fournisseur")
    public ResponseEntity<ApiResponse<SupplierOrderDto>> createOrder(
            @Valid @RequestBody CreateSupplierOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Bon de commande créé", supplierOrderService.createOrder(request)));
    }

    @GetMapping
    @Operation(summary = "Lister les bons de commande (filtres : supplierId, status)")
    public ResponseEntity<ApiResponse<PageResponse<SupplierOrderDto>>> getOrders(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) SupplierOrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.success(supplierOrderService.getOrders(supplierId, status, pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtenir un bon de commande avec ses paiements et réceptions")
    public ResponseEntity<ApiResponse<SupplierOrderDto>> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(supplierOrderService.getOrder(id)));
    }

    @PatchMapping("/{id}/confirm")
    @Operation(summary = "Confirmer un bon de commande (BROUILLON → CONFIRME)")
    public ResponseEntity<ApiResponse<SupplierOrderDto>> confirmOrder(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Bon confirmé", supplierOrderService.confirmOrder(id)));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Annuler un bon de commande")
    public ResponseEntity<ApiResponse<SupplierOrderDto>> cancelOrder(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Bon annulé", supplierOrderService.cancelOrder(id)));
    }

    @PostMapping("/{id}/payments")
    @Operation(
        summary = "Enregistrer un paiement (avance ou solde)",
        description = """
            Ajoute un paiement au bon de commande.
            Le montant cumulé ne peut pas dépasser le total de la commande.
            Modes : ESPECES, VIREMENT, CHEQUE, VIREMENT_INSTANTANE.
            """
    )
    public ResponseEntity<ApiResponse<SupplierOrderDto>> recordPayment(
            @PathVariable Long id,
            @Valid @RequestBody RecordPaymentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Paiement enregistré", supplierOrderService.recordPayment(id, request)));
    }

    @DeleteMapping("/{orderId}/payments/{paymentId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Supprimer un paiement")
    public ResponseEntity<ApiResponse<Void>> deletePayment(
            @PathVariable Long orderId, @PathVariable Long paymentId) {
        supplierOrderService.deletePayment(orderId, paymentId);
        return ResponseEntity.ok(ApiResponse.success("Paiement supprimé"));
    }

    @PostMapping("/{id}/deliveries")
    @Operation(
        summary = "Enregistrer une réception (lot)",
        description = """
            Enregistre la réception d'un lot de marchandises.
            - Met à jour les quantités reçues sur chaque ligne
            - Met à jour le stock physique du variant lié
            - Recalcule le CMUP (Coût Moyen Unitaire Pondéré) du variant
            - Passe le bon en EN_COURS ou COMPLETE automatiquement
            Le champ unitCost par ligne est optionnel — si absent, utilise le coût de commande.
            """
    )
    public ResponseEntity<ApiResponse<SupplierOrderDto>> recordDelivery(
            @PathVariable Long id,
            @Valid @RequestBody RecordDeliveryRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Réception enregistrée", supplierOrderService.recordDelivery(id, request)));
    }
}
