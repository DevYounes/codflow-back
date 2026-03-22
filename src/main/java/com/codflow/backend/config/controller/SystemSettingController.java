package com.codflow.backend.config.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.config.dto.SettingRequest;
import com.codflow.backend.config.entity.SystemSetting;
import com.codflow.backend.config.repository.SystemSettingRepository;
import com.codflow.backend.config.service.SystemSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Paramètres", description = "Configuration de l'application")
public class SystemSettingController {

    private final SystemSettingService settingService;
    private final SystemSettingRepository settingRepository;

    @GetMapping
    @Operation(summary = "Lister tous les paramètres")
    public ResponseEntity<ApiResponse<List<SystemSetting>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(settingRepository.findAll()));
    }

    @GetMapping("/{key}")
    @Operation(summary = "Obtenir un paramètre par clé")
    public ResponseEntity<ApiResponse<String>> get(@PathVariable String key) {
        String value = settingService.get(key).orElse(null);
        return ResponseEntity.ok(ApiResponse.success(value));
    }

    @PutMapping("/{key}")
    @Operation(summary = "Mettre à jour un paramètre")
    public ResponseEntity<ApiResponse<Void>> set(@PathVariable String key,
                                                  @RequestBody SettingRequest request) {
        settingService.set(key, request.getValue(), request.getDescription());
        return ResponseEntity.ok(ApiResponse.success("Paramètre mis à jour"));
    }
}
