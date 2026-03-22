package com.codflow.backend.delivery.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.delivery.dto.CreateShipmentRequest;
import com.codflow.backend.delivery.dto.DeliveryShipmentDto;
import com.codflow.backend.delivery.dto.RequestPickupDto;
import com.codflow.backend.delivery.entity.DeliveryProviderConfig;
import com.codflow.backend.delivery.provider.DeliveryProviderRegistry;
import com.codflow.backend.delivery.repository.DeliveryProviderRepository;
import com.codflow.backend.delivery.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/delivery")
@RequiredArgsConstructor
@Tag(name = "Delivery", description = "Gestion des livraisons")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final DeliveryProviderRepository providerRepository;
    private final DeliveryProviderRegistry providerRegistry;

    @PostMapping("/shipments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Créer un envoi pour une commande confirmée")
    public ResponseEntity<ApiResponse<DeliveryShipmentDto>> createShipment(
            @Valid @RequestBody CreateShipmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Envoi créé avec succès", deliveryService.createShipment(request)));
    }

    @PostMapping("/pickup")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Synchroniser le suivi d'un envoi")
    public ResponseEntity<ApiResponse<DeliveryShipmentDto>> syncTracking(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Suivi mis à jour", deliveryService.syncTracking(id)));
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
}
