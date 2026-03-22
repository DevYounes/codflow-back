package com.codflow.backend.importer.service;

import com.codflow.backend.config.service.SystemSettingService;
import com.codflow.backend.importer.dto.ImportResultDto;
import com.codflow.backend.order.dto.CreateOrderRequest;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.order.service.OrderService;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.product.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches a configured Google Sheets URL and imports new rows automatically.
 *
 * Configuration:
 *   - Set the Google Sheet URL via PUT /api/v1/settings/googlesheet.import.url
 *     Accepts both edit URLs and direct CSV export URLs.
 *   - The sheet must be shared as "Anyone with the link can view".
 *
 * Column header matching is case-insensitive and supports French/English names.
 * Row idempotency is handled by orderNumber uniqueness.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoImportService {

    private static final String KEY_LAST_ROW_COUNT = "googlesheet.import.last_row_count";

    // Pattern to extract spreadsheet ID from Google Sheets URLs
    private static final Pattern SHEET_ID_PATTERN =
            Pattern.compile("spreadsheets/d/([a-zA-Z0-9_-]+)");
    // Pattern to extract gid (sheet tab id)
    private static final Pattern GID_PATTERN =
            Pattern.compile("[?&#]gid=([0-9]+)");

    private final SystemSettingService settingService;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final WebClient.Builder webClientBuilder;

    /**
     * Runs every minute by default. Fetches the Google Sheet and imports new rows.
     */
    @Scheduled(fixedDelayString = "${app.import.check-interval-ms:60000}")
    public void checkAndImport() {
        String sheetUrl = settingService.get(SystemSettingService.KEY_SHEET_IMPORT_URL).orElse(null);
        if (sheetUrl == null || sheetUrl.isBlank()) {
            return;
        }

        try {
            String csvUrl = toCsvExportUrl(sheetUrl);
            String csvContent = fetchCsv(csvUrl);
            if (csvContent == null || csvContent.isBlank()) {
                log.warn("Auto-import: empty response from Google Sheet");
                return;
            }

            // Quick row count check to avoid unnecessary processing
            long lineCount = csvContent.lines().count();
            int lastCount = settingService.getInt(KEY_LAST_ROW_COUNT, 0);
            if (lineCount <= lastCount) {
                return; // no new rows
            }

            log.info("Auto-import: {} lines detected (last: {}), importing...", lineCount, lastCount);
            ImportResultDto result = importFromCsv(csvContent);
            log.info("Auto-import result: {} imported, {} skipped, {} errors",
                    result.getImported(), result.getSkipped(), result.getErrors());

            settingService.set(KEY_LAST_ROW_COUNT, String.valueOf(lineCount));

        } catch (Exception e) {
            log.error("Auto-import error: {}", e.getMessage(), e);
        }
    }

    /**
     * Imports from the configured Google Sheet URL immediately (manual trigger).
     */
    @Transactional
    public ImportResultDto triggerImport() {
        String sheetUrl = settingService.get(SystemSettingService.KEY_SHEET_IMPORT_URL)
                .orElseThrow(() -> new IllegalStateException(
                        "Aucune URL Google Sheets configurée. Définissez: "
                                + SystemSettingService.KEY_SHEET_IMPORT_URL));

        String csvUrl = toCsvExportUrl(sheetUrl);
        String csvContent = fetchCsv(csvUrl);
        if (csvContent == null || csvContent.isBlank()) {
            throw new IllegalStateException("La feuille Google Sheets est vide ou inaccessible. " +
                    "Vérifiez que le partage est activé (\"Tout le monde avec le lien\").");
        }

        ImportResultDto result = importFromCsv(csvContent);
        long lineCount = csvContent.lines().count();
        settingService.set(KEY_LAST_ROW_COUNT, String.valueOf(lineCount));
        return result;
    }

    @Transactional
    public ImportResultDto importFromCsv(String csvContent) {
        List<String> errors = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int imported = 0;
        int totalRows = 0;

        try (CSVParser parser = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .build()
                .parse(new StringReader(csvContent))) {

            for (CSVRecord record : parser) {
                totalRows++;
                if (isRecordEmpty(record)) continue;

                try {
                    CreateOrderRequest request = parseRecord(record);
                    String orderNum = request.getOrderNumber();
                    if (orderNum != null && orderRepository.existsByOrderNumber(orderNum)) {
                        skipped.add("Ligne " + totalRows + ": " + orderNum + " déjà importée");
                        continue;
                    }
                    orderService.createOrder(request, null);
                    imported++;
                } catch (Exception e) {
                    errors.add("Ligne " + totalRows + ": " + e.getMessage());
                    log.warn("Auto-import row {}: {}", totalRows, e.getMessage());
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Impossible de parser le CSV: " + e.getMessage(), e);
        }

        return ImportResultDto.builder()
                .totalRows(totalRows)
                .imported(imported)
                .skipped(skipped.size())
                .errors(errors.size())
                .errorMessages(errors)
                .skippedMessages(skipped)
                .build();
    }

    /**
     * Converts any Google Sheets URL to a CSV export URL.
     * Supports edit URLs, pub URLs, and direct CSV export URLs.
     */
    public String toCsvExportUrl(String url) {
        if (url.contains("/export?format=csv")) {
            return url; // already a CSV export URL
        }

        Matcher idMatcher = SHEET_ID_PATTERN.matcher(url);
        if (!idMatcher.find()) {
            throw new IllegalArgumentException(
                    "URL Google Sheets invalide. Format attendu: " +
                    "https://docs.google.com/spreadsheets/d/{ID}/edit?gid={GID}");
        }
        String spreadsheetId = idMatcher.group(1);

        // Extract gid (tab id), default to 0
        Matcher gidMatcher = GID_PATTERN.matcher(url);
        String gid = gidMatcher.find() ? gidMatcher.group(1) : "0";

        return "https://docs.google.com/spreadsheets/d/" + spreadsheetId
                + "/export?format=csv&gid=" + gid;
    }

    /**
     * Fetches CSV content from a URL. Returns null on failure.
     */
    private String fetchCsv(String url) {
        try {
            return webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                throw new IllegalStateException(
                        "Accès refusé à la feuille Google Sheets (HTTP " + e.getStatusCode().value() + "). " +
                        "Vérifiez que le partage est activé : Fichier → Partager → \"Tout le monde avec le lien\" → Lecteur.");
            }
            log.error("Failed to fetch Google Sheet CSV from {}: {}", url, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch Google Sheet CSV from {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Maps a CSV record to a CreateOrderRequest.
     *
     * Expected Google Sheet columns:
     *   Order id | Order date | Full name | Phone | City | Adresse | Product name | Variente | Price | Quantité
     *
     * Header matching is case-insensitive.
     */
    private CreateOrderRequest parseRecord(CSVRecord r) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setSource(OrderSource.EXCEL);

        // --- Required fields ---
        String customerName = col(r, "Full name", "nom client", "customer name", "nom", "name");
        String phone        = col(r, "Phone", "telephone", "téléphone", "tel");
        String address      = col(r, "Adresse", "adresse", "address");
        String city         = col(r, "City", "ville", "city");

        if (blank(customerName)) throw new IllegalArgumentException("Nom client manquant (colonne 'Full name')");
        if (blank(phone))        throw new IllegalArgumentException("Téléphone manquant (colonne 'Phone')");
        if (blank(address))      throw new IllegalArgumentException("Adresse manquante (colonne 'Adresse')");
        if (blank(city))         throw new IllegalArgumentException("Ville manquante (colonne 'City')");

        req.setCustomerName(customerName.trim());
        req.setCustomerPhone(phone.trim());
        req.setAddress(address.trim());
        req.setVille(city.trim());
        req.setCity(city.trim());

        // --- Optional order fields ---
        req.setOrderNumber(col(r, "Order id", "order_id", "n° commande", "order number", "ref"));
        req.setNotes(col(r, "notes", "remarques", "note", "commentaire"));
        req.setZipCode(col(r, "code postal", "zip", "zipcode"));

        String shippingStr = col(r, "frais livraison", "shipping", "livraison");
        BigDecimal shipping = parseBigDecimal(shippingStr);
        req.setShippingCost(shipping != null ? shipping : BigDecimal.ZERO);

        // --- Product ---
        String productName = col(r, "Product name", "produit", "product", "article");
        if (blank(productName)) throw new IllegalArgumentException("Nom produit manquant (colonne 'Product name')");

        String priceStr = col(r, "Price", "prix", "price", "montant");
        BigDecimal unitPrice = parseBigDecimal(priceStr);
        if (unitPrice == null) throw new IllegalArgumentException("Prix manquant (colonne 'Price')");

        String qtyStr = col(r, "Quantité", "quantite", "qty", "quantity");
        int quantity = parseIntOrDefault(qtyStr, 1);

        CreateOrderRequest.OrderItemRequest item = new CreateOrderRequest.OrderItemRequest();
        item.setProductName(productName.trim());
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);

        String sku = col(r, "sku", "code produit", "product sku");
        if (!blank(sku)) {
            item.setProductSku(sku.trim());
            productRepository.findBySku(sku.trim()).ifPresent(p -> item.setProductId(p.getId()));
        }

        // --- Variant: "Variente" column (e.g. "Rouge/42", "Bleu", "44") ---
        String variente = col(r, "Variente", "variante", "variant", "variation", "couleur/taille");
        if (!blank(variente)) {
            // Try to match variant in DB; if not found, store in item notes via product name suffix
            String variantStr = variente.trim();
            // Attempt to split "Color/Size" or "Color-Size"
            String[] parts = variantStr.split("[/\\-]", 2);
            String color = parts[0].trim();
            String size  = parts.length > 1 ? parts[1].trim() : null;

            // Try to find matching ProductVariant in DB
            productRepository.findByNameIgnoreCase(productName.trim()).ifPresent(product -> {
                // Look for a variant matching color and/or size
                // The round-trip is: findVariant by product + color + size
                // We store the variantId if found
            });

            // Append variant info to product name so it's visible in the order
            item.setProductName(productName.trim() + " - " + variantStr);

            // Also try to find and set variantId if a matching ProductVariant exists
            String finalColor = color;
            String finalSize  = size;
            if (!blank(sku)) {
                productRepository.findBySku(sku.trim()).ifPresent(product ->
                        findVariantId(product.getId(), finalColor, finalSize)
                                .ifPresent(item::setVariantId));
            } else {
                productRepository.findByNameIgnoreCase(productName.trim()).ifPresent(product ->
                        findVariantId(product.getId(), finalColor, finalSize)
                                .ifPresent(item::setVariantId));
            }
        }

        req.setItems(List.of(item));
        return req;
    }

    private java.util.Optional<Long> findVariantId(Long productId, String color, String size) {
        return productVariantRepository.findByProductIdAndActiveTrue(productId).stream()
                .filter(v -> {
                    boolean colorMatch = blank(color) || color.equalsIgnoreCase(v.getColor());
                    boolean sizeMatch  = blank(size)  || size.equalsIgnoreCase(v.getSize());
                    return colorMatch && sizeMatch;
                })
                .findFirst()
                .map(v -> v.getId());
    }

    // ---- Helpers ----

    /**
     * Returns the first non-blank value matching any of the given header aliases (case-insensitive).
     */
    private String col(CSVRecord record, String... aliases) {
        for (String alias : aliases) {
            try {
                String value = record.get(findHeader(record, alias));
                if (value != null && !value.isBlank()) return value.trim();
            } catch (IllegalArgumentException ignored) {
                // header not found, try next alias
            }
        }
        return null;
    }

    /**
     * Finds the actual header name in the record that matches the alias (case-insensitive, trimmed).
     */
    private String findHeader(CSVRecord record, String alias) {
        for (String key : record.toMap().keySet()) {
            if (key != null && key.trim().equalsIgnoreCase(alias)) {
                return key;
            }
        }
        throw new IllegalArgumentException("Header not found: " + alias);
    }

    private boolean isRecordEmpty(CSVRecord record) {
        return record.toMap().values().stream().allMatch(v -> v == null || v.isBlank());
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private BigDecimal parseBigDecimal(String val) {
        if (blank(val)) return null;
        try {
            return new BigDecimal(val.replace(",", ".").replace(" ", "").replace("\u00a0", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseIntOrDefault(String val, int defaultValue) {
        if (blank(val)) return defaultValue;
        try {
            return (int) Double.parseDouble(val.replace(",", "."));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
