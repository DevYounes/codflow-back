package com.codflow.backend.importer.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.importer.dto.ImportResultDto;
import com.codflow.backend.importer.service.AutoImportService;
import com.codflow.backend.importer.service.ExcelImportService;
import com.codflow.backend.importer.service.HistoricalExcelMigrationService;
import com.codflow.backend.importer.service.OrderCostBackfillService;
import com.codflow.backend.importer.service.ShopifyHistoricalCsvImportService;
import com.codflow.backend.importer.service.ShopifyImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/import")
@RequiredArgsConstructor
@Tag(name = "Import", description = "Import de commandes depuis Google Sheets / Excel")
public class ImportController {

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    private final ExcelImportService excelImportService;
    private final AutoImportService autoImportService;
    private final ShopifyImportService shopifyImportService;
    private final HistoricalExcelMigrationService historicalExcelMigrationService;
    private final ShopifyHistoricalCsvImportService shopifyHistoricalCsvImportService;
    private final OrderCostBackfillService orderCostBackfillService;

    /**
     * Manual upload of an Excel file (.xlsx).
     * NOTE: currently disabled — Shopify is the only active import source.
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
     * NOTE: currently disabled — Shopify is the only active import source.
     * Configure the URL first via: PUT /api/v1/settings/googlesheet.import.url
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

    /**
     * Step 1 of OAuth: returns the Shopify authorization URL.
     * Pre-requisites (configure via PUT /api/v1/settings/{key}):
     *   shopify.store.domain   → castello.myshopify.com
     *   shopify.app.client_id  → Client ID from Dev Dashboard
     *   shopify.app.client_secret → Client Secret from Dev Dashboard
     *
     * The redirectUri must match EXACTLY what is configured in the Dev Dashboard app.
     */
    @GetMapping("/shopify/oauth/start")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Démarrer l'authentification OAuth Shopify",
        description = """
            Génère l'URL d'autorisation Shopify OAuth. Ouvrez l'URL retournée dans votre navigateur pour autoriser l'app.
            Pré-requis: shopify.store.domain, shopify.app.client_id, shopify.app.client_secret configurés via PUT /api/v1/settings/{key}.
            Le redirect_uri est construit automatiquement depuis app.backend.url (application.yml).
            """
    )
    public ResponseEntity<ApiResponse<Map<String, String>>> startShopifyOAuth() {
        try {
            String oauthUrl = shopifyImportService.startOAuth();
            return ResponseEntity.ok(ApiResponse.success(
                    "URL OAuth générée. Ouvrez cette URL dans votre navigateur pour autoriser l'application.",
                    Map.of("oauthUrl", oauthUrl)));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Step 2 of OAuth: Shopify redirects here after authorization.
     * This endpoint is PUBLIC (no JWT required) — Shopify calls it via browser redirect.
     * Configure this URL in the Dev Dashboard: https://api.codflow.ma/api/v1/import/shopify/oauth/callback
     */
    /**
     * Step 2 of OAuth — Shopify redirects here after authorization.
     * This endpoint is PUBLIC (no JWT). After processing, redirects browser to frontend.
     * The redirectUri used in /oauth/start MUST point to this backend endpoint directly,
     * e.g. http://localhost:8080/api/v1/import/shopify/oauth/callback
     */
    @GetMapping("/shopify/oauth/callback")
    @Operation(summary = "Callback OAuth Shopify (appelé automatiquement par Shopify après autorisation)")
    public void shopifyOAuthCallback(
            @RequestParam String shop,
            @RequestParam String code,
            @RequestParam String state,
            HttpServletResponse response) throws IOException {
        try {
            shopifyImportService.completeOAuth(shop, code, state);
            response.sendRedirect(frontendUrl + "/dashboard?shopify=connected");
        } catch (Exception e) {
            log.error("Shopify OAuth callback failed: {}", e.getMessage());
            response.sendRedirect(frontendUrl + "/dashboard?shopify=error&message="
                    + java.net.URLEncoder.encode(e.getMessage(), java.nio.charset.StandardCharsets.UTF_8));
        }
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

    /**
     * Resets the Shopify since_id cursor.
     * Use sinceId=0 to force a full re-import of all orders.
     * Use a specific Shopify order ID to resume from that point.
     * WARNING: setting sinceId=0 may re-import thousands of already-imported orders
     * (they will be skipped via shopifyOrderId deduplication, but the process may be slow).
     */
    @PostMapping("/shopify/reset-since-id")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Réinitialiser le curseur since_id de l'import Shopify",
        description = """
            Réinitialise le curseur de synchronisation Shopify (since_id).
            sinceId=0 → réimport complet depuis le début (les doublons sont ignorés automatiquement).
            sinceId=<N> → reprend depuis l'ordre ID N.
            Utile pour diagnostiquer ou récupérer des commandes manquées.
            """
    )
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetShopifySinceId(
            @RequestParam(value = "sinceId", defaultValue = "0") long sinceId) {
        shopifyImportService.resetSinceId(sinceId);
        return ResponseEntity.ok(ApiResponse.success(
                "Curseur since_id réinitialisé à " + sinceId + ". Le prochain import démarrera depuis cet ID.",
                Map.of("sinceId", sinceId)));
    }

    /**
     * One-shot migration endpoint — upload your Excel tracking file (File 2) to update
     * order statuses and create delivery shipments from real historical data.
     *
     * Required Excel columns (detected automatically by header name):
     *   - "Order ID"               → Shopify order ID
     *   - "Status de confirmation" → confirmation status
     *   - "Statut de livraison"    → delivery/shipment status
     *   - "Tracking number"        → Ozon tracking code
     *   - "Delivred Fee"           → per-order delivery fee (e.g. "35dh")
     *
     * Default fees are used when a row has no fee value.
     * The endpoint is idempotent: already-updated LIVRE/RETOURNE orders are skipped.
     */
    @PostMapping(value = "/shopify/historical/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Migration historique depuis Excel (one-shot)",
        description = """
            Upload le fichier Excel de suivi (Fichier 2) pour mettre à jour les statuts des
            commandes déjà importées depuis Shopify et créer les shipments de livraison/retour.
            Colonnes détectées automatiquement: Order ID, Status de confirmation,
            Statut de livraison, Tracking number, Delivred Fee.
            Les frais par défaut sont utilisés quand la colonne "Delivred Fee" est absente ou vide.
            Opération idempotente: les commandes déjà en LIVRE/RETOURNE sont ignorées.
            À utiliser une seule fois après l'import historique Shopify.
            """
    )
    public ResponseEntity<ApiResponse<ImportResultDto>> historicalExcelMigration(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "defaultDeliveryFee", defaultValue = "25") java.math.BigDecimal defaultDeliveryFee,
            @RequestParam(value = "defaultReturnFee",   defaultValue = "10") java.math.BigDecimal defaultReturnFee) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Le fichier est vide"));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls"))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Format attendu: .xlsx ou .xls"));
        }
        try {
            ImportResultDto result = historicalExcelMigrationService.migrateFromExcel(
                    file, defaultDeliveryFee, defaultReturnFee);
            return ResponseEntity.ok(ApiResponse.success(
                    String.format("Migration terminée: %d mis à jour, %d ignorées, %d erreurs",
                            result.getImported(), result.getSkipped(), result.getErrors()),
                    result));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Import historique depuis un export CSV natif Shopify (Admin → Commandes →
     * Exporter). Permet de récupérer les commandes plus anciennes que 60 jours,
     * inaccessibles via l'API REST sans le scope read_all_orders.
     *
     * Le CSV Shopify contient une ligne par article ; les commandes multi-articles
     * y apparaissent sur plusieurs lignes (même valeur "Name"). Le service les
     * regroupe avant création.
     *
     * Idempotent : les commandes dont le order_number ou le shopify_order_id
     * sont déjà en base sont ignorées.
     *
     * Paramètre defaultStatus :
     *   - "auto" (défaut) → mapping basé sur Cancelled at, Fulfillment Status
     *                       et Financial Status du CSV
     *   - "LIVRE", "ANNULE", etc. → force toutes les commandes à ce statut
     */
    @PostMapping(value = "/shopify/historical/orders-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Import historique des commandes depuis un export CSV Shopify",
        description = """
            Crée les commandes manquantes à partir d'un export CSV natif Shopify.
            Utile pour récupérer les commandes plus anciennes que 60 jours, qui
            ne sont pas accessibles via l'API REST (limite Shopify, scope
            read_all_orders requis).
            Idempotent : les commandes déjà en base (order_number ou shopify_order_id)
            sont ignorées.
            """
    )
    public ResponseEntity<ApiResponse<ImportResultDto>> importShopifyHistoricalCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "defaultStatus", defaultValue = "auto") String defaultStatus) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Le fichier est vide"));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Format attendu: .csv (export Shopify natif)"));
        }
        try {
            ImportResultDto result = shopifyHistoricalCsvImportService
                    .importFromShopifyCsv(file, defaultStatus);
            return ResponseEntity.ok(ApiResponse.success(
                    String.format("Import CSV Shopify terminé: %d créées, %d ignorées, %d erreurs",
                            result.getImported(), result.getSkipped(), result.getErrors()),
                    result));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Relie les articles de commandes (OrderItem) sans produit aux produits/variantes
     * existants via leur SKU, et snapshote le unitCost depuis le costPrice du produit.
     *
     * À lancer UNE SEULE FOIS après :
     *   1. Import des commandes Shopify (→ items sans produit lié)
     *   2. Création des produits/variantes avec leurs SKU et prix de revient
     *
     * Idempotent : les items déjà liés sont ignorés.
     * Les items sans SKU ou avec SKU non trouvé sont listés pour correction manuelle.
     */
    @PostMapping("/backfill-order-costs")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Relier les articles de commande aux produits (backfill coûts)",
        description = """
            Scanne tous les articles de commande non liés à un produit et tente de les
            relier via le SKU Shopify. Snapshote automatiquement le unitCost depuis le
            costPrice du produit/variante trouvé.
            Opération idempotente — peut être relancée après ajout de nouveaux produits.
            Les articles sans SKU ou avec SKU non trouvé sont listés pour action manuelle.
            """
    )
    public ResponseEntity<ApiResponse<ImportResultDto>> backfillOrderCosts() {
        try {
            ImportResultDto result = orderCostBackfillService.backfill();
            return ResponseEntity.ok(ApiResponse.success(
                    String.format("Backfill terminé: %d liés, %d non résolus sur %d items",
                            result.getImported(), result.getErrors(), result.getTotalRows()),
                    result));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error(e.getMessage()));
        }
    }
}
