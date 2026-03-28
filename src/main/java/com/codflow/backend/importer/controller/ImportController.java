package com.codflow.backend.importer.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.importer.dto.ImportResultDto;
import com.codflow.backend.importer.service.AutoImportService;
import com.codflow.backend.importer.service.ExcelImportService;
import com.codflow.backend.importer.service.ShopifyImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/import")
@RequiredArgsConstructor
@Tag(name = "Import", description = "Import de commandes depuis Google Sheets / Excel")
public class ImportController {

    private final ExcelImportService excelImportService;
    private final AutoImportService autoImportService;
    private final ShopifyImportService shopifyImportService;

    /**
     * Manual upload of an Excel file (.xlsx).
     */
    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Importer depuis un fichier Excel uploadé manuellement")
    public ResponseEntity<ApiResponse<ImportResultDto>> importFromExcel(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Le fichier est vide"));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls"))) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Format attendu: .xlsx ou .xls"));
        }
        ImportResultDto result = excelImportService.importFromExcel(file);
        return ResponseEntity.ok(ApiResponse.success(
                String.format("Import terminé: %d importées, %d ignorées, %d erreurs",
                        result.getImported(), result.getSkipped(), result.getErrors()),
                result));
    }

    /**
     * Triggers an immediate import from the configured Google Sheets URL.
     * Configure the URL first via: PUT /api/v1/settings/googlesheet.import.url
     *
     * The Google Sheet must be shared as "Anyone with the link can view".
     *
     * Expected columns:
     *   Order id | Order date | Full name | Phone | City | Adresse | Product name | Variente | Price | Quantité
     */
    @PostMapping("/googlesheet/trigger")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(
        summary = "Déclencher manuellement l'import depuis Google Sheets",
        description = """
            Importe les nouvelles lignes depuis la feuille Google Sheets configurée.
            Colonnes attendues: Order id | Order date | Full name | Phone | City | Adresse | Product name | Variente | Price | Quantité
            Pré-requis: définir l'URL via PUT /api/v1/settings/googlesheet.import.url
            Le partage doit être activé sur "Tout le monde avec le lien peut voir".
            """
    )
    public ResponseEntity<ApiResponse<ImportResultDto>> triggerGoogleSheetsImport() {
        try {
            ImportResultDto result = autoImportService.triggerImport();
            return ResponseEntity.ok(ApiResponse.success(
                    String.format("Import terminé: %d importées, %d ignorées, %d erreurs",
                            result.getImported(), result.getSkipped(), result.getErrors()),
                    result));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/shopify/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Statut de la configuration Shopify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shopifyStatus() {
        return ResponseEntity.ok(ApiResponse.success(shopifyImportService.getStatus()));
    }

    @PostMapping("/shopify/trigger")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(
        summary = "Déclencher manuellement l'import depuis Shopify",
        description = """
            Importe les nouvelles commandes Shopify depuis le dernier ordre synchronisé.
            Pré-requis: définir shopify.store.domain et shopify.access.token via PUT /api/v1/settings/{key}.
            L'import est incrémental: seules les nouvelles commandes (depuis le dernier ID synchronisé) sont importées.
            """
    )
    public ResponseEntity<ApiResponse<ImportResultDto>> triggerShopifyImport() {
        try {
            ImportResultDto result = shopifyImportService.triggerImport();
            return ResponseEntity.ok(ApiResponse.success(
                    String.format("Import Shopify terminé: %d importées, %d ignorées, %d erreurs",
                            result.getImported(), result.getSkipped(), result.getErrors()),
                    result));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
