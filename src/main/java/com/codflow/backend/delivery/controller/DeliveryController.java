package com.codflow.backend.delivery.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.delivery.dto.ConfirmReturnRequest;
import com.codflow.backend.delivery.dto.CreateDeliveryNoteRequest;
import com.codflow.backend.delivery.dto.CreateShipmentRequest;
import com.codflow.backend.delivery.dto.DeliveryNoteDto;
import com.codflow.backend.delivery.dto.DeliveryShipmentDto;
import com.codflow.backend.delivery.dto.PendingReturnDto;
import com.codflow.backend.delivery.dto.RequestPickupDto;
import com.codflow.backend.delivery.dto.UpdateProviderConfigRequest;
import com.codflow.backend.delivery.service.DeliveryNoteService;
import com.codflow.backend.delivery.entity.DeliveryProviderConfig;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import com.codflow.backend.delivery.provider.DeliveryProviderRegistry;
import com.codflow.backend.delivery.repository.DeliveryProviderRepository;
import com.codflow.backend.delivery.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/delivery")
@RequiredArgsConstructor
@Tag(name = "Delivery", description = "Gestion des livraisons")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final DeliveryNoteService deliveryNoteService;
    private final DeliveryProviderRepository providerRepository;
    private final DeliveryProviderRegistry providerRegistry;

    @GetMapping("/shipments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT', 'MAGASINIER')")
    @Operation(summary = "Lister les envois avec filtres optionnels")
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<DeliveryShipmentDto>>> listShipments(
            @RequestParam(required = false) List<ShipmentStatus> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("asc") ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(ApiResponse.success(deliveryService.listShipments(status, search, from, to, pageable)));
    }

    @GetMapping("/shipments/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT', 'MAGASINIER')")
    @Operation(summary = "Obtenir un envoi par ID")
    public ResponseEntity<ApiResponse<DeliveryShipmentDto>> getShipment(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(deliveryService.getShipmentById(id)));
    }

    @PostMapping("/shipments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT', 'MAGASINIER')")
    @Operation(summary = "Créer un envoi pour une commande confirmée")
    public ResponseEntity<ApiResponse<DeliveryShipmentDto>> createShipment(
            @Valid @RequestBody CreateShipmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Envoi créé avec succès", deliveryService.createShipment(request)));
    }

    @PostMapping("/pickup")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MAGASINIER')")
    @Operation(summary = "Demander un ramassage auprès du transporteur")
    public ResponseEntity<ApiResponse<Void>> requestPickup(@Valid @RequestBody RequestPickupDto request) {
        deliveryService.requestPickup(request);
        return ResponseEntity.ok(ApiResponse.success("Demande de ramassage envoyée avec succès"));
    }

    @GetMapping("/shipments/order/{orderId}")
    @Operation(summary = "Obtenir l'envoi d'une commande")
    public ResponseEntity<ApiResponse<DeliveryShipmentDto>> getShipmentByOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.success(deliveryService.getShipmentByOrderId(orderId)));
    }

    @PostMapping("/shipments/{id}/sync")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT', 'MAGASINIER')")
    @Operation(summary = "Synchroniser le suivi d'un envoi")
    public ResponseEntity<ApiResponse<DeliveryShipmentDto>> syncTracking(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Suivi mis à jour", deliveryService.syncTracking(id)));
    }

    // =========================================================================
    // Suivi des retours physiques
    // =========================================================================

    @GetMapping("/returns")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT', 'MAGASINIER')")
    @Operation(
        summary = "Colis en attente de retour physique",
        description = """
            Liste tous les colis refusés / en échec de livraison / annulés dont
            le retour physique n'a pas encore été confirmé par le marchand.
            Chaque entrée indique le nombre de jours d'attente et un flag 'overdue'
            si > 7 jours (risque que le transporteur ne retourne pas le colis).
            """
    )
    public ResponseEntity<ApiResponse<List<PendingReturnDto>>> getPendingReturns() {
        return ResponseEntity.ok(ApiResponse.success(deliveryService.getPendingReturns()));
    }

    @PostMapping("/shipments/{id}/confirm-return")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT', 'MAGASINIER')")
    @Operation(
        summary = "Confirmer la réception physique d'un colis retourné",
        description = """
            Marque le colis comme physiquement reçu en retour par le marchand.
            Applicable aux colis en statut FAILED_DELIVERY, RETURNED ou CANCELLED.
            Optionnel : ajouter des notes (état du colis, remarques...).
            """
    )
    public ResponseEntity<ApiResponse<DeliveryShipmentDto>> confirmReturn(
            @PathVariable Long id,
            @RequestBody(required = false) ConfirmReturnRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Retour confirmé", deliveryService.confirmReturnReceived(id, request)));
    }

    @PostMapping("/shipments/repair-fees")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Réparer les frais de livraison manquants",
        description = "Re-fetche les tarifs Ozon et backfille delivered_price/applied_fee pour les colis sans prix."
    )
    public ResponseEntity<ApiResponse<String>> repairShipmentFees() {
        int count = deliveryService.repairShipmentFees();
        return ResponseEntity.ok(ApiResponse.success(count + " colis réparés"));
    }

    @GetMapping("/providers")
    @Operation(summary = "Lister les transporteurs disponibles")
    public ResponseEntity<ApiResponse<List<DeliveryProviderConfig>>> getProviders() {
        return ResponseEntity.ok(ApiResponse.success(providerRepository.findByActiveTrue()));
    }

    @GetMapping("/providers/registered")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lister les transporteurs enregistrés dans le système")
    public ResponseEntity<ApiResponse<List<String>>> getRegisteredProviders() {
        return ResponseEntity.ok(ApiResponse.success(providerRegistry.getRegisteredProviderCodes()));
    }

    @PutMapping("/providers/{id}/config")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mettre à jour les credentials d'un transporteur (apiKey, apiToken, apiBaseUrl)")
    public ResponseEntity<ApiResponse<DeliveryProviderConfig>> updateProviderConfig(
            @PathVariable Long id,
            @RequestBody UpdateProviderConfigRequest request) {
        DeliveryProviderConfig provider = providerRepository.findById(id)
                .orElseThrow(() -> new com.codflow.backend.common.exception.ResourceNotFoundException("Transporteur", id));

        if (request.getApiKey() != null)     provider.setApiKey(request.getApiKey());
        if (request.getApiToken() != null)   provider.setApiToken(request.getApiToken());
        if (request.getApiBaseUrl() != null) provider.setApiBaseUrl(request.getApiBaseUrl());
        if (request.getActive() != null)     provider.setActive(request.getActive());

        return ResponseEntity.ok(ApiResponse.success("Configuration mise à jour", providerRepository.save(provider)));
    }

    // =========================================================================
    // Bons de Livraison (Delivery Notes)
    // =========================================================================

    @PostMapping("/notes")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT', 'MAGASINIER')")
    @Operation(
        summary = "Créer un bon de livraison (BL)",
        description = """
            Exécute le flux complet en 3 étapes auprès d'Ozon Express:
            1. Crée le BL (add-delivery-note) → obtient la référence
            2. Ajoute les colis au BL (add-parcel-to-delivery-note)
            3. Sauvegarde le BL (save-delivery-note)
            Retourne la référence BL + les liens PDF (standard, étiquettes A4, étiquettes 10x10cm).
            """
    )
    public ResponseEntity<ApiResponse<DeliveryNoteDto>> createDeliveryNote(
            @Valid @RequestBody CreateDeliveryNoteRequest request) {
        try {
            DeliveryNoteDto note = deliveryNoteService.createNote(request);
            return ResponseEntity.ok(ApiResponse.success(
                    "Bon de livraison créé — réf: " + note.getRef(), note));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/notes")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT', 'MAGASINIER')")
    @Operation(summary = "Lister les bons de livraison")
    public ResponseEntity<ApiResponse<Page<DeliveryNoteDto>>> listDeliveryNotes(
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "20")   int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(ApiResponse.success(deliveryNoteService.listNotes(pageable)));
    }

    @GetMapping("/notes/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT', 'MAGASINIER')")
    @Operation(summary = "Obtenir un bon de livraison par ID")
    public ResponseEntity<ApiResponse<DeliveryNoteDto>> getDeliveryNote(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(deliveryNoteService.getNote(id)));
    }

    @GetMapping("/notes/ref/{ref}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT', 'MAGASINIER')")
    @Operation(summary = "Obtenir un bon de livraison par référence BL")
    public ResponseEntity<ApiResponse<DeliveryNoteDto>> getDeliveryNoteByRef(@PathVariable String ref) {
        return ResponseEntity.ok(ApiResponse.success(deliveryNoteService.getNoteByRef(ref)));
    }
}
