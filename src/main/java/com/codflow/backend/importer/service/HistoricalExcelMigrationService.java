package com.codflow.backend.importer.service;

import com.codflow.backend.delivery.entity.DeliveryProviderConfig;
import com.codflow.backend.delivery.entity.DeliveryShipment;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import com.codflow.backend.delivery.repository.DeliveryProviderRepository;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import com.codflow.backend.importer.dto.ImportResultDto;
import com.codflow.backend.order.entity.Order;
import com.codflow.backend.order.enums.OrderStatus;
import com.codflow.backend.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-shot service to migrate historical order statuses from an Excel tracking file (File 2).
 *
 * Expected Excel columns (detected by header name, case-insensitive):
 *   - "order id"              → Shopify order ID (e.g. 6867698450661)
 *   - "status de confirmation" or "confirmation" → confirmation status
 *   - "statut de livraison" or "livraison"       → delivery status
 *   - "tracking" or "tracking number"            → Ozon tracking number
 *   - "delivred fee" or "delivery fee" or "frais livraison" → actual delivery fee (e.g. "35dh")
 *   - "packaging"                                → packaging fee (e.g. "10dh")
 *
 * Status mapping logic (delivery status takes priority over confirmation status):
 *   Delivery "Livré"                → LIVRE   + DELIVERED shipment
 *   Delivery "Retourné"             → RETOURNE + RETURNED shipment
 *   Delivery "Expédié"              → ENVOYE
 *   Delivery "Attente De Ramassage" → CONFIRME
 *   Confirmation "Pas sérieux"      → PAS_SERIEUX
 *   Confirmation "Fake order"       → FAKE_ORDER
 *   Confirmation "Annulé"           → ANNULE
 *   Confirmation "Injoignable"      → INJOIGNABLE
 *   Confirmation "Boîte Vocal"      → BOITE_VOCAL
 *   Confirmation "Appel + Message"  → APPEL_PLUS_MESSAGE
 *   Confirmation "Appel 3"          → APPEL_3
 *   Confirmation "Confirmé" (no delivery) → CONFIRME
 *   (anything else)                 → NOUVEAU (unchanged)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalExcelMigrationService {

    private final OrderRepository             orderRepository;
    private final DeliveryShipmentRepository  shipmentRepository;
    private final DeliveryProviderRepository  providerRepository;

    @Transactional
    public ImportResultDto migrateFromExcel(MultipartFile file, BigDecimal defaultDeliveryFee,
                                            BigDecimal defaultReturnFee) {
        List<String> errors  = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int updated   = 0;
        int totalRows = 0;

        DeliveryProviderConfig provider = providerRepository.findByActiveTrue()
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Aucun transporteur actif configuré"));

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            // ── 1. Detect column indices from header row ──────────────────────
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new IllegalArgumentException("Le fichier Excel est vide (aucune ligne d'en-tête)");

            Map<String, Integer> cols = detectColumns(headerRow);
            log.info("[EXCEL-MIGRATION] Colonnes détectées: {}", cols);

            Integer colOrderId      = cols.get("order_id");
            Integer colConfirmation = cols.get("confirmation");
            Integer colDelivery     = cols.get("delivery");
            Integer colTracking     = cols.get("tracking");
            Integer colFee          = cols.get("fee");

            if (colOrderId == null) {
                throw new IllegalArgumentException(
                        "Colonne 'Order ID' introuvable dans le fichier. " +
                        "Vérifiez que la première ligne contient bien un en-tête 'Order ID'.");
            }

            // ── 2. Process each data row ──────────────────────────────────────
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowEmpty(row)) continue;
                totalRows++;

                String rawOrderId = getCellString(row, colOrderId);
                if (rawOrderId == null || rawOrderId.isBlank()) {
                    skipped.add("Ligne " + (r + 1) + ": Order ID manquant");
                    continue;
                }

                // Order IDs from Excel may be numeric (stored as double), normalize
                String shopifyOrderId = rawOrderId.replaceAll("\\.0+$", "").trim();

                Order order = orderRepository.findByShopifyOrderId(shopifyOrderId).orElse(null);
                if (order == null) {
                    skipped.add("Ligne " + (r + 1) + " [" + shopifyOrderId + "]: commande introuvable en base (pas encore importée depuis Shopify)");
                    continue;
                }

                String confirmationRaw = colConfirmation != null ? getCellString(row, colConfirmation) : null;
                String deliveryRaw     = colDelivery     != null ? getCellString(row, colDelivery)     : null;
                String tracking        = colTracking     != null ? getCellString(row, colTracking)      : null;
                String feeRaw          = colFee          != null ? getCellString(row, colFee)           : null;

                BigDecimal fee = parseFee(feeRaw);

                try {
                    OrderStatus targetStatus = mapStatus(confirmationRaw, deliveryRaw);

                    // Skip rows where we can't determine a useful status
                    if (targetStatus == null) {
                        skipped.add("Ligne " + (r + 1) + " [" + shopifyOrderId + "]: statut non reconnu "
                                + "(confirmation='" + confirmationRaw + "', livraison='" + deliveryRaw + "')");
                        continue;
                    }

                    // Don't downgrade an already-terminal status
                    if (order.getStatus() == OrderStatus.LIVRE || order.getStatus() == OrderStatus.RETOURNE) {
                        if (targetStatus != OrderStatus.LIVRE && targetStatus != OrderStatus.RETOURNE) {
                            skipped.add("Ligne " + (r + 1) + " [" + shopifyOrderId + "]: commande déjà en statut "
                                    + order.getStatus() + " — ignorée");
                            continue;
                        }
                    }

                    LocalDateTime now = LocalDateTime.now();
                    order.setStatus(targetStatus);

                    if (targetStatus == OrderStatus.CONFIRME || targetStatus == OrderStatus.ENVOYE
                            || targetStatus == OrderStatus.LIVRE || targetStatus == OrderStatus.RETOURNE) {
                        if (order.getConfirmedAt() == null) order.setConfirmedAt(now);
                    }
                    if (targetStatus.isCancelled()) {
                        if (order.getCancelledAt() == null) order.setCancelledAt(now);
                    }

                    orderRepository.save(order);

                    // Create shipment for LIVRE / RETOURNE if none exists yet
                    if (targetStatus == OrderStatus.LIVRE) {
                        if (!shipmentRepository.existsByOrderId(order.getId())) {
                            BigDecimal actualFee = fee != null ? fee : defaultDeliveryFee;
                            createShipment(order, provider, ShipmentStatus.DELIVERED, actualFee, "LIVRAISON", tracking);
                        }
                    } else if (targetStatus == OrderStatus.RETOURNE) {
                        if (!shipmentRepository.existsByOrderId(order.getId())) {
                            BigDecimal actualFee = fee != null ? fee : defaultReturnFee;
                            createShipment(order, provider, ShipmentStatus.RETURNED, actualFee, "RETOUR", tracking);
                        }
                    }

                    updated++;
                    log.info("[EXCEL-MIGRATION] {} → {} (tracking={})", shopifyOrderId, targetStatus, tracking);

                } catch (Exception e) {
                    errors.add("Ligne " + (r + 1) + " [" + shopifyOrderId + "]: " + e.getMessage());
                    log.warn("[EXCEL-MIGRATION] Erreur ligne {}: {}", r + 1, e.getMessage(), e);
                }
            }

        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Impossible de lire le fichier Excel: " + e.getMessage(), e);
        }

        log.info("[EXCEL-MIGRATION] Terminé: {} mis à jour, {} ignorés, {} erreurs sur {} lignes",
                updated, skipped.size(), errors.size(), totalRows);

        return ImportResultDto.builder()
                .totalRows(totalRows)
                .imported(updated)
                .skipped(skipped.size())
                .errors(errors.size())
                .errorMessages(errors)
                .skippedMessages(skipped)
                .build();
    }

    // ── Column detection ──────────────────────────────────────────────────────

    private Map<String, Integer> detectColumns(Row header) {
        Map<String, Integer> result = new HashMap<>();
        for (int c = 0; c <= header.getLastCellNum(); c++) {
            String h = getCellString(header, c);
            if (h == null) continue;
            String lower = normalize(h);

            if (lower.contains("order") && lower.contains("id")) {
                result.putIfAbsent("order_id", c);
            } else if (lower.contains("confirmation") || lower.contains("statut de confirmation")
                    || lower.contains("status de confirmation")) {
                result.putIfAbsent("confirmation", c);
            } else if ((lower.contains("livraison") && lower.contains("statut"))
                    || lower.equals("statut de livraison")
                    || lower.equals("livraison")) {
                result.putIfAbsent("delivery", c);
            } else if (lower.contains("tracking")) {
                result.putIfAbsent("tracking", c);
            } else if (lower.contains("delivred") || lower.contains("delivery fee")
                    || lower.contains("frais livraison") || lower.contains("frais de livraison")) {
                result.putIfAbsent("fee", c);
            }
        }
        return result;
    }

    // ── Status mapping ────────────────────────────────────────────────────────

    /**
     * Returns the CodFlow OrderStatus based on the Excel row's confirmation and delivery statuses.
     * Returns null if neither column contains a recognizable value.
     */
    private OrderStatus mapStatus(String confirmation, String delivery) {
        // Delivery status takes priority (it's the final state)
        if (delivery != null && !delivery.isBlank()) {
            String d = normalize(delivery);
            if (d.contains("livr") && !d.contains("retour"))  return OrderStatus.LIVRE;
            if (d.contains("retourn"))                        return OrderStatus.RETOURNE;
            if (d.contains("exped") || d.contains("expedi"))  return OrderStatus.ENVOYE;
            if (d.contains("attente") || d.contains("ramassage")) return OrderStatus.CONFIRME;
        }

        // Fall back to confirmation status
        if (confirmation != null && !confirmation.isBlank()) {
            String c = normalize(confirmation);
            if (c.contains("pas") && c.contains("serieux"))   return OrderStatus.PAS_SERIEUX;
            if (c.contains("fake"))                            return OrderStatus.FAKE_ORDER;
            if (c.contains("annul"))                           return OrderStatus.ANNULE;
            if (c.contains("injoignable"))                     return OrderStatus.INJOIGNABLE;
            if (c.contains("boite") || c.contains("vocal"))   return OrderStatus.BOITE_VOCAL;
            if (c.contains("appel") && c.contains("message")) return OrderStatus.APPEL_PLUS_MESSAGE;
            if (c.contains("appel") && c.contains("3"))       return OrderStatus.APPEL_3;
            if (c.contains("appel") && c.contains("2"))       return OrderStatus.APPEL_2;
            if (c.contains("appel"))                           return OrderStatus.APPEL_1;
            if (c.contains("confirm"))                         return OrderStatus.CONFIRME;
            if (c.contains("doublon"))                         return OrderStatus.DOUBLON;
            if (c.contains("non confirm"))                     return OrderStatus.NOUVEAU;
        }

        return null; // unrecognized — skip
    }

    // ── Shipment creation ─────────────────────────────────────────────────────

    private void createShipment(Order order, DeliveryProviderConfig provider,
                                ShipmentStatus status, BigDecimal fee,
                                String feeType, String tracking) {
        DeliveryShipment shipment = new DeliveryShipment();
        shipment.setOrder(order);
        shipment.setProvider(provider);
        shipment.setStatus(status);
        shipment.setTrackingNumber(tracking != null && !tracking.isBlank() ? tracking : null);
        shipment.setAppliedFee(fee);
        shipment.setAppliedFeeType(feeType);
        shipment.setNotes("Migration historique Excel");

        LocalDateTime now = LocalDateTime.now();
        if (status == ShipmentStatus.DELIVERED) {
            shipment.setDeliveredAt(now);
            shipment.setDeliveredPrice(fee);
        } else {
            shipment.setReturnedAt(now);
            shipment.setReturnedPrice(fee);
        }
        shipmentRepository.save(shipment);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Parse fee values like "35dh", "45 dh", "20.00" → BigDecimal */
    private BigDecimal parseFee(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Remove everything that's not a digit or decimal separator
        String numeric = raw.replaceAll("[^0-9.,]", "").replace(",", ".").trim();
        if (numeric.isBlank()) return null;
        try { return new BigDecimal(numeric); }
        catch (NumberFormatException e) { return null; }
    }

    private String normalize(String s) {
        if (s == null) return "";
        return java.text.Normalizer
                .normalize(s.toLowerCase().trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "") // strip accents
                .replaceAll("\\s+", " ");
    }

    private boolean isRowEmpty(Row row) {
        for (int i = 0; i <= row.getLastCellNum(); i++) {
            String v = getCellString(row, i);
            if (v != null && !v.isBlank()) return false;
        }
        return true;
    }

    private String getCellString(Row row, int col) {
        if (col < 0) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val))
                    yield String.valueOf((long) val);
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue().trim(); }
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> null;
        };
    }
}
