package com.codflow.backend.importer.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.config.service.SystemSettingService;
import com.codflow.backend.importer.dto.ImportResultDto;
import com.codflow.backend.importer.service.AutoImportService;
import com.codflow.backend.importer.service.ExcelImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Paths;

@RestController
@RequestMapping("/api/v1/import")
@RequiredArgsConstructor
@Tag(name = "Import", description = "Import de commandes depuis Excel/Shopify")
public class ImportController {

    private final ExcelImportService excelImportService;
    private final AutoImportService autoImportService;
    private final SystemSettingService settingService;

    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(
        summary = "Importer les commandes depuis un fichier Excel",
        description = """
            Format attendu du fichier Excel (.xlsx):
            - Colonne A: Numéro de commande (optionnel)
            - Colonne B: Nom du client (obligatoire)
            - Colonne C: Téléphone (obligatoire)
            - Colonne D: Téléphone 2 (optionnel)
            - Colonne E: Adresse (obligatoire)
            - Colonne F: Ville (obligatoire)
            - Colonne G: Ville (optionnel)
            - Colonne H: Nom du produit (obligatoire)
            - Colonne I: SKU produit (optionnel)
            - Colonne J: Quantité (défaut: 1)
            - Colonne K: Prix unitaire (obligatoire)
            - Colonne L: Frais de livraison (optionnel)
            - Colonne M: Notes (optionnel)
            """
    )
    public ResponseEntity<ApiResponse<ImportResultDto>> importFromExcel(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Le fichier est vide"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls"))) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Le fichier doit être au format Excel (.xlsx ou .xls)"));
        }

        ImportResultDto result = excelImportService.importFromExcel(file);
        String message = String.format("Import terminé: %d importées, %d ignorées, %d erreurs",
                result.getImported(), result.getSkipped(), result.getErrors());
        return ResponseEntity.ok(ApiResponse.success(message, result));
    }

    @PostMapping("/excel/trigger")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Déclencher manuellement l'import depuis le fichier Excel configuré")
    public ResponseEntity<ApiResponse<ImportResultDto>> triggerAutoImport() {
        String filePath = settingService.get(SystemSettingService.KEY_EXCEL_IMPORT_PATH).orElse(null);
        if (filePath == null || filePath.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Aucun fichier Excel configuré. Définissez le paramètre: "
                            + SystemSettingService.KEY_EXCEL_IMPORT_PATH));
        }
        ImportResultDto result = autoImportService.importFromFile(Paths.get(filePath));
        String message = String.format("Import terminé: %d importées, %d ignorées, %d erreurs",
                result.getImported(), result.getSkipped(), result.getErrors());
        return ResponseEntity.ok(ApiResponse.success(message, result));
    }
}
