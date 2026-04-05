package com.codflow.backend.importer.service;

import com.codflow.backend.importer.dto.ImportResultDto;
import com.codflow.backend.order.entity.OrderItem;
import com.codflow.backend.order.repository.OrderItemRepository;
import com.codflow.backend.product.entity.Product;
import com.codflow.backend.product.entity.ProductVariant;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.product.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Opération one-shot : relie les OrderItem sans produit aux produits/variantes
 * existants, d'abord via SKU, puis via le nom du produit (fallback sans SKU).
 *
 * Formats de noms supportés (commandes Shopify sans SKU configuré) :
 *   "VENISE Marron - 40"     → produit "VENISE Marron", taille "40"
 *   "Venise - Marron - 40"   → produit "Venise Marron", taille "40"
 *   "PALERME Noir - 41"      → produit "PALERME Noir",  taille "41"
 *
 * Le service est idempotent : les items déjà liés (product != null) sont ignorés.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCostBackfillService {

    private final OrderItemRepository     orderItemRepository;
    private final ProductRepository       productRepository;
    private final ProductVariantRepository variantRepository;

    @Transactional
    public ImportResultDto backfill() {
        List<OrderItem> unlinked = orderItemRepository.findByProductIsNull();

        int matched   = 0;
        int unmatched = 0;
        List<String> unmatchedDetails = new ArrayList<>();

        // Chargement unique de tous les produits pour le matching par nom (évite N+1)
        List<Product> allProducts = productRepository.findAll();

        for (OrderItem item : unlinked) {
            String sku = item.getProductSku();

            // ── Matching par SKU ───────────────────────────────────────────────
            if (sku != null && !sku.isBlank()) {
                // 1. SKU variante (exact puis case-insensitive)
                Optional<ProductVariant> variantOpt = variantRepository.findByVariantSku(sku);
                if (variantOpt.isEmpty()) variantOpt = variantRepository.findByVariantSkuIgnoreCase(sku);
                if (variantOpt.isPresent()) {
                    ProductVariant v = variantOpt.get();
                    linkItem(item, v.getProduct(), v);
                    log.info("[BACKFILL-SKU] Item #{} → variante '{}' unitCost={}",
                            item.getId(), v.getVariantSku(), item.getUnitCost());
                    matched++;
                    continue;
                }

                // 2. SKU produit (exact puis case-insensitive)
                Optional<Product> productOpt = productRepository.findBySku(sku);
                if (productOpt.isEmpty()) productOpt = productRepository.findBySkuIgnoreCase(sku);
                if (productOpt.isPresent()) {
                    linkItem(item, productOpt.get(), null);
                    log.info("[BACKFILL-SKU] Item #{} → produit '{}' unitCost={}",
                            item.getId(), productOpt.get().getName(), item.getUnitCost());
                    matched++;
                    continue;
                }

                log.warn("[BACKFILL-SKU] SKU '{}' introuvable — tentative matching par nom pour item #{}",
                        sku, item.getId());
            }

            // ── Matching par nom de produit (fallback pour items sans SKU) ────
            // Formats : "VENISE Marron - 42" ou "Venise - Marron - 42"
            // → split " - ", last = taille, reste joints par " " = nom produit
            String[] parts = item.getProductName().split(" - ");
            if (parts.length >= 2) {
                String size        = parts[parts.length - 1].trim();
                String[] nameParts = java.util.Arrays.copyOf(parts, parts.length - 1);
                String candidateName = String.join(" ", nameParts).trim();

                Optional<Product> byName = findProductByName(allProducts, candidateName);
                if (byName.isPresent()) {
                    Product p = byName.get();
                    Optional<ProductVariant> variantOpt =
                            variantRepository.findFirstByProductIdAndSizeIgnoreCase(p.getId(), size);
                    if (variantOpt.isPresent()) {
                        linkItem(item, p, variantOpt.get());
                        log.info("[BACKFILL-NAME] Item #{} '{}' → produit '{}' taille '{}' unitCost={}",
                                item.getId(), item.getProductName(), p.getName(), size, item.getUnitCost());
                        matched++;
                        continue;
                    } else {
                        // Produit trouvé mais pas la taille → lier quand même au produit
                        linkItem(item, p, null);
                        log.warn("[BACKFILL-NAME] Item #{} '{}' → produit '{}' trouvé mais taille '{}' absente",
                                item.getId(), item.getProductName(), p.getName(), size);
                        matched++;
                        continue;
                    }
                }
            }

            // Aucun matching possible
            unmatched++;
            String skuInfo = (sku != null && !sku.isBlank()) ? " SKU='" + sku + "'" : "";
            unmatchedDetails.add(
                    "Item #" + item.getId()
                    + " (commande " + item.getOrder().getOrderNumber() + ")"
                    + " — '" + item.getProductName() + "'" + skuInfo + " : introuvable");
            log.warn("[BACKFILL] Introuvable — item #{} '{}'", item.getId(), item.getProductName());
        }

        log.info("[BACKFILL] Terminé: {} liés, {} non résolus sur {} items total",
                matched, unmatched, unlinked.size());

        return ImportResultDto.builder()
                .totalRows(unlinked.size())
                .imported(matched)
                .skipped(0)
                .errors(unmatched)
                .errorMessages(unmatchedDetails)
                .skippedMessages(List.of())
                .build();
    }

    /** Lie l'item au produit/variante et snapshote unitCost si pas encore défini. */
    private void linkItem(OrderItem item, Product product, ProductVariant variant) {
        item.setProduct(product);
        item.setVariant(variant);
        if (item.getUnitCost() == null) {
            if (variant != null && variant.getCostPrice() != null) {
                item.setUnitCost(variant.getCostPrice());
            } else if (product.getCostPrice() != null) {
                item.setUnitCost(product.getCostPrice());
            }
        }
        orderItemRepository.save(item);
    }

    /**
     * Cherche un produit par nom dans la liste en mémoire, en normalisant la casse et
     * en remplaçant " - " par " " pour matcher les deux formats Shopify.
     *
     * Exemples :
     *   candidate = "Venise Marron"   → matche produit "VENISE Marron"
     *   candidate = "Venise  Marron"  → matche après normalisation des espaces
     */
    private Optional<Product> findProductByName(List<Product> products, String candidate) {
        String normalized = normalize(candidate);
        return products.stream()
                .filter(p -> normalize(p.getName()).equals(normalized))
                .findFirst();
    }

    /** Lowercase + collapse whitespace + strip accents for fuzzy comparison. */
    private String normalize(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase().trim();
        // "venise - marron" et "venise marron" doivent matcher
        lower = lower.replace(" - ", " ").replace("-", " ");
        // Supprimer les accents
        lower = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        // Normaliser les espaces multiples
        return lower.replaceAll("\\s+", " ");
    }
}
