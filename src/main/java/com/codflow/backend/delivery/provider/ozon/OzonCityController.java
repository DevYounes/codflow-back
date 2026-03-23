package com.codflow.backend.delivery.provider.ozon;

import com.codflow.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the Ozon Express city list to the frontend.
 * Agents use this dropdown to map the customer's free-text city to the official Ozon city ID.
 */
@RestController
@RequestMapping("/api/v1/ozon")
@RequiredArgsConstructor
@Tag(name = "Ozon Express", description = "Intégration société de livraison Ozon Express")
public class OzonCityController {

    private final OzonCityService ozonCityService;

    @GetMapping("/cities")
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN', 'MANAGER')")
    @Operation(summary = "Liste des villes Ozon Express (pour sélecteur agent)")
    public ResponseEntity<ApiResponse<List<OzonCityDto>>> getCities() {
        return ResponseEntity.ok(ApiResponse.success(ozonCityService.getCities()));
    }
}
