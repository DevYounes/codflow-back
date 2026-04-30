package com.codflow.backend.importer.service;

import com.codflow.backend.common.util.PhoneNormalizer;
import com.codflow.backend.customer.entity.Customer;
import com.codflow.backend.customer.enums.CustomerStatus;
import com.codflow.backend.customer.repository.CustomerRepository;
import com.codflow.backend.importer.dto.ImportResultDto;
import com.codflow.backend.order.entity.Order;
import com.codflow.backend.order.entity.OrderItem;
import com.codflow.backend.order.entity.OrderStatusHistory;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.enums.OrderStatus;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.order.repository.OrderStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Importe les commandes Shopify historiques (au-delà des 60 jours, non
 * accessibles via l'API REST sans le scope read_all_orders) à partir d'un
 * export CSV natif Shopify (Admin → Commandes → Exporter).
 *
 * Le format Shopify émet une ligne par article ; toutes les lignes d'une même
 * commande partagent la même valeur "Name" (ex. "#1001"). On regroupe donc par
 * Name avant la création.
 *
 * Pas de @Transactional sur la méthode d'import : chaque commande s'écrit dans
 * sa propre transaction implicite (orderRepository.save) ; un échec n'empoisonne
 * pas la session pour les commandes suivantes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopifyHistoricalCsvImportService {

    private static final Set<String> NULL_TOKENS = Set.of("", "null", "NULL");

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;

    /**
     * @param defaultStatusOverride si non null/auto, force toutes les commandes à
     *                              ce statut (ex. "LIVRE"). Sinon mapping auto
     *                              à partir des colonnes Shopify.
     */
    public ImportResultDto importFromShopifyCsv(MultipartFile file, String defaultStatusOverride) {
        OrderStatus statusOverride = parseStatusOverride(defaultStatusOverride);

        Map<String, List<Map<String, String>>> rowsByOrderName = parseAndGroup(file);

        List<String> errors = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int imported = 0;

        for (Map.Entry<String, List<Map<String, String>>> entry : rowsByOrderName.entrySet()) {
            String name = entry.getKey();
            List<Map<String, String>> rows = entry.getValue();
            try {
                ImportOutcome outcome = importOneOrder(name, rows, statusOverride);
                switch (outcome.kind) {
                    case IMPORTED -> imported++;
                    case SKIPPED  -> skipped.add(outcome.message);
                    case ERROR    -> errors.add(outcome.message);
                }
            } catch (Exception e) {
                errors.add(name + " : " + e.getMessage());
                log.warn("[SHOPIFY-CSV] Erreur import {} : {}", name, e.getMessage(), e);
            }
        }

        log.info("[SHOPIFY-CSV] Import historique terminé : {} importées, {} ignorées, {} erreurs",
                imported, skipped.size(), errors.size());

        return ImportResultDto.builder()
                .totalRows(rowsByOrderName.size())
                .imported(imported)
                .skipped(skipped.size())
                .errors(errors.size())
                .errorMessages(errors)
                .skippedMessages(skipped)
                .build();
    }

    // ── Parsing CSV ──────────────────────────────────────────────────────────

    /**
     * Parse le CSV en mémoire et regroupe les lignes par "Name". On stocke
     * Map<String,String> et pas CSVRecord pour libérer le parser après lecture.
     */
    private Map<String, List<Map<String, String>>> parseAndGroup(MultipartFile file) {
        Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
        try (Reader reader = new BufferedReader(new InputStreamReader(
                                stripBom(file.getInputStream()), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setIgnoreSurroundingSpaces(true)
                     .build()
                     .parse(reader)) {

            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (!headerMap.containsKey("Name")) {
                throw new IllegalArgumentException(
                        "Colonne 'Name' introuvable dans l'export. Vérifie qu'il s'agit bien de l'export Shopify natif.");
            }

            for (CSVRecord record : parser) {
                String name = safe(record.get("Name"));
                if (blank(name)) continue;
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> h : headerMap.entrySet()) {
                    rowMap.put(h.getKey(), safe(record.get(h.getKey())));
                }
                grouped.computeIfAbsent(name, k -> new ArrayList<>()).add(rowMap);
            }
        } catch (IOException e) {
            throw new RuntimeException("Impossible de lire le CSV : " + e.getMessage(), e);
        }
        return grouped;
    }

    /** Skip the BOM (EF BB BF) si présent en tête de fichier. */
    private java.io.InputStream stripBom(java.io.InputStream in) throws IOException {
        java.io.PushbackInputStream p = new java.io.PushbackInputStream(in, 3);
        byte[] head = new byte[3];
        int read = p.read(head);
        if (read == 3 && head[0] == (byte) 0xEF && head[1] == (byte) 0xBB && head[2] == (byte) 0xBF) {
            return p; // BOM consommé
        }
        if (read > 0) p.unread(head, 0, read);
        return p;
    }

    // ── Création d'une commande ──────────────────────────────────────────────

    private enum OutcomeKind { IMPORTED, SKIPPED, ERROR }
    private record ImportOutcome(OutcomeKind kind, String message) {
        static ImportOutcome imported()             { return new ImportOutcome(OutcomeKind.IMPORTED, null); }
        static ImportOutcome skipped(String reason) { return new ImportOutcome(OutcomeKind.SKIPPED, reason); }
    }

    /**
     * Crée une commande historique. Pas de @Transactional volontairement :
     * chaque save() (Customer, Order, StatusHistory) tourne dans sa propre tx
     * implicite courte → un échec sur Order n'empoisonne pas la session pour
     * la commande suivante. Les éventuels Customer orphelins seront réutilisés
     * au prochain run via findByPhoneNormalized.
     */
    private ImportOutcome importOneOrder(String name, List<Map<String, String>> rows, OrderStatus statusOverride) {
        Map<String, String> first = rows.get(0);
        String orderNumber = name.replace("#", "").trim();
        String shopifyId = nullToEmpty(first.get("Id"));

        if (orderRepository.existsByOrderNumberIncludingDeleted(orderNumber)) {
            return ImportOutcome.skipped(name + " : numéro déjà en base");
        }
        if (!blank(shopifyId) && orderRepository.existsByShopifyOrderId(shopifyId)) {
            return ImportOutcome.skipped(name + " : shopify_id déjà en base");
        }

        Order order = buildOrder(rows, orderNumber, shopifyId, statusOverride);
        Order saved = orderRepository.save(order);

        // Recale les dates historiques (Spring Data Auditing écrase createdAt sur persist)
        LocalDateTime createdAt   = parseDate(first.get("Created at"));
        LocalDateTime confirmedAt = saved.getConfirmedAt();
        LocalDateTime cancelledAt = saved.getCancelledAt();
        if (createdAt != null || confirmedAt != null || cancelledAt != null) {
            orderRepository.overrideHistoricalDates(saved.getId(), createdAt, confirmedAt, cancelledAt);
        }

        // Historique de statut : NOUVEAU → status final si différent
        recordHistory(saved, OrderStatus.NOUVEAU, "Import historique Shopify (CSV)");
        if (saved.getStatus() != OrderStatus.NOUVEAU) {
            recordHistory(saved, saved.getStatus(), "Statut récupéré depuis l'export Shopify");
        }

        log.info("[SHOPIFY-CSV] Commande {} créée → status={}", orderNumber, saved.getStatus());
        return ImportOutcome.imported();
    }

    private Order buildOrder(List<Map<String, String>> rows, String orderNumber,
                             String shopifyId, OrderStatus statusOverride) {
        Map<String, String> first = rows.get(0);

        Order order = new Order();
        order.setOrderNumber(orderNumber);
        order.setSource(OrderSource.SHOPIFY);
        if (!blank(shopifyId)) order.setShopifyOrderId(shopifyId);

        // ── Client (préfère Shipping, fallback Billing) ────────────────────
        String customerName = firstNonBlank(first.get("Shipping Name"), first.get("Billing Name"));
        if (blank(customerName)) customerName = "Client inconnu";
        order.setCustomerName(customerName);

        String phone = firstNonBlank(
                first.get("Shipping Phone"), first.get("Billing Phone"), first.get("Phone"));
        if (blank(phone)) {
            phone = "0000000000";
            log.warn("[SHOPIFY-CSV] Commande {} : téléphone manquant, placeholder utilisé", orderNumber);
        }
        order.setCustomerPhone(phone);
        order.setCustomerPhoneNormalized(PhoneNormalizer.normalize(phone));

        // ── Adresse ───────────────────────────────────────────────────────
        String addr1 = firstNonBlank(first.get("Shipping Address1"), first.get("Billing Address1"));
        String addr2 = firstNonBlank(first.get("Shipping Address2"), first.get("Billing Address2"));
        String address = addr1;
        if (!blank(addr2)) address = blank(address) ? addr2 : address + ", " + addr2;
        if (blank(address)) address = "À compléter";
        order.setAddress(address);

        String city = firstNonBlank(first.get("Shipping City"), first.get("Billing City"));
        if (blank(city)) city = "À compléter";
        order.setCity(city);
        order.setVille(city);

        order.setZipCode(firstNonBlank(first.get("Shipping Zip"), first.get("Billing Zip")));

        // ── Frais et notes ────────────────────────────────────────────────
        order.setShippingCost(parseDecimal(first.get("Shipping")));

        StringBuilder notes = new StringBuilder();
        appendIf(notes, first.get("Notes"));
        String tags = first.get("Tags");
        if (!blank(tags)) {
            if (notes.length() > 0) notes.append(" | ");
            notes.append("Tags: ").append(tags);
        }
        if (notes.length() > 0) order.setNotes(notes.toString());

        // ── Articles (un par ligne du CSV pour cette commande) ────────────
        for (Map<String, String> row : rows) {
            String itemName = row.get("Lineitem name");
            if (blank(itemName)) continue;
            OrderItem item = new OrderItem();
            item.setProductName(itemName);
            String sku = row.get("Lineitem sku");
            if (!blank(sku)) item.setProductSku(sku);
            int qty = parseInt(row.get("Lineitem quantity"), 1);
            item.setQuantity(qty);
            BigDecimal price = parseDecimal(row.get("Lineitem price"));
            item.setUnitPrice(price);
            item.setTotalPrice(price.multiply(BigDecimal.valueOf(qty)));
            order.addItem(item);
        }
        order.recalculateTotals();

        // ── Lien client ───────────────────────────────────────────────────
        order.setCustomer(findOrCreateCustomer(order));

        // ── Status mapping + dates ────────────────────────────────────────
        OrderStatus status = statusOverride != null ? statusOverride : mapStatus(first);
        order.setStatus(status);

        if (status.isCancelled()) {
            LocalDateTime t = parseDate(first.get("Cancelled at"));
            order.setCancelledAt(t != null ? t : LocalDateTime.now());
        }
        if (status == OrderStatus.LIVRE
                || status == OrderStatus.CONFIRME
                || status == OrderStatus.RETOURNE
                || status == OrderStatus.ENVOYE) {
            LocalDateTime t = parseDate(first.get("Paid at"));
            if (t == null) t = parseDate(first.get("Fulfilled at"));
            if (t == null) t = parseDate(first.get("Created at"));
            order.setConfirmedAt(t);
        }

        // ── Stock : ne pas toucher pour les imports historiques ───────────
        order.setStockReserved(false);
        order.setStockDeducted(false);

        return order;
    }

    private Customer findOrCreateCustomer(Order order) {
        return customerRepository.findByPhoneNormalized(order.getCustomerPhoneNormalized())
                .orElseGet(() -> {
                    Customer c = new Customer();
                    c.setPhone(order.getCustomerPhone());
                    c.setPhoneNormalized(order.getCustomerPhoneNormalized());
                    c.setFullName(order.getCustomerName());
                    c.setAddress(order.getAddress());
                    c.setVille(order.getVille());
                    c.setStatus(CustomerStatus.ACTIVE);
                    return customerRepository.save(c);
                });
    }

    private void recordHistory(Order order, OrderStatus toStatus, String note) {
        OrderStatusHistory h = new OrderStatusHistory();
        h.setOrder(order);
        h.setToStatus(toStatus);
        h.setNotes(note);
        statusHistoryRepository.save(h);
    }

    /**
     * Mapping basé sur Cancelled at + Fulfillment Status + Financial Status.
     *   Cancelled at present                       → ANNULE
     *   Fulfillment Status = "fulfilled"           → LIVRE
     *   Fulfillment Status = "partial"             → CONFIRME
     *   Financial Status   = "refunded"/"voided"   → ANNULE
     *   Financial Status   = "paid"                → CONFIRME
     *   sinon                                       → NOUVEAU
     */
    private OrderStatus mapStatus(Map<String, String> row) {
        if (!blank(row.get("Cancelled at"))) return OrderStatus.ANNULE;

        String fulfillment = lower(row.get("Fulfillment Status"));
        if ("fulfilled".equals(fulfillment)) return OrderStatus.LIVRE;
        if ("partial".equals(fulfillment))   return OrderStatus.CONFIRME;

        String financial = lower(row.get("Financial Status"));
        if ("refunded".equals(financial) || "voided".equals(financial)) return OrderStatus.ANNULE;
        if ("paid".equals(financial)) return OrderStatus.CONFIRME;

        return OrderStatus.NOUVEAU;
    }

    private OrderStatus parseStatusOverride(String s) {
        if (blank(s) || "auto".equalsIgnoreCase(s)) return null;
        try {
            return OrderStatus.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Status invalide : " + s
                    + ". Valeurs supportées : auto, NOUVEAU, CONFIRME, LIVRE, ANNULE, RETOURNE, etc.");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Shopify émet "2026-01-26 13:43:00 +0000" ou ISO-8601. On tente plusieurs
     * formats et retourne null si rien ne match.
     */
    private LocalDateTime parseDate(String raw) {
        if (blank(raw)) return null;
        String s = raw.trim();
        // ISO-8601 avec offset (ex. "2026-01-26T13:43:00+00:00")
        try { return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); }
        catch (Exception ignored) {}
        // "2026-01-26 13:43:00 +0000"
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
            return OffsetDateTime.parse(s, f).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {}
        // "2026-01-26 13:43:00" (sans offset)
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(s, f);
        } catch (Exception ignored) {}
        // Epoch ms (au cas où)
        try { return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(s)), ZoneId.systemDefault()); }
        catch (Exception ignored) {}
        log.warn("[SHOPIFY-CSV] Date non parsée : '{}'", raw);
        return null;
    }

    private BigDecimal parseDecimal(String raw) {
        if (blank(raw)) return BigDecimal.ZERO;
        try { return new BigDecimal(raw.replace(",", ".").trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private int parseInt(String raw, int defaultValue) {
        if (blank(raw)) return defaultValue;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private String firstNonBlank(String... values) {
        for (String v : values) if (!blank(v)) return v.trim();
        return null;
    }

    private void appendIf(StringBuilder sb, String value) {
        if (!blank(value)) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(value);
        }
    }

    private String safe(String s) { return s == null ? "" : s; }
    private String nullToEmpty(String s) { return s == null ? "" : s; }
    private boolean blank(String s) { return s == null || NULL_TOKENS.contains(s.trim()); }
    private String lower(String s) { return s == null ? "" : s.trim().toLowerCase(); }
}
