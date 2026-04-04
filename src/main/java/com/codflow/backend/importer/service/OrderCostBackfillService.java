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
 * existants via leur SKU, et snapshote le unitCost depuis le costPrice du produit.
 *
 * À lancer après :
 *   1. Import Shopify (commandes importées sans produits encore créés)
 *   2. Création des produits/variantes avec leurs SKU et costPrice
 *
 * Le service est idempotent : les items déjà liés (product != null) sont ignorés.
 * Pour les items sans SKU ou avec un SKU non trouvé, ils restent inchangés et sont
 * listés dans le rapport pour correction manuelle.
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

        for (OrderItem item : unlinked) {
            String sku = item.getProductSku();

            if (sku == null || sku.isBlank()) {
                unmatched++;
                unmatchedDetails.add(
                        "Item #" + item.getId()
                        + " (commande " + item.getOrder().getOrderNumber() + ")"
                        + " — '" + item.getProductName() + "' : aucun SKU, liaison manuelle requise");
                continue;
            }

            // 1. Essayer SKU variante
            Optional<ProductVariant> variantOpt = variantRepository.findByVariantSku(sku);
            if (variantOpt.isPresent()) {
                ProductVariant v = variantOpt.get();
                item.setVariant(v);
                item.setProduct(v.getProduct());
                if (item.getUnitCost() == null) {
                    item.setUnitCost(resolveVariantCost(v));
                }
                orderItemRepository.save(item);
                log.info("[BACKFILL] Item #{} → variante '{}' (produit '{}') unitCost={}",
                        item.getId(), v.getVariantSku(), v.getProduct().getName(), item.getUnitCost());
                matched++;
                continue;
            }

            // 2. Essayer SKU produit
            Optional<Product> productOpt = productRepository.findBySku(sku);
            if (productOpt.isPresent()) {
                Product p = productOpt.get();
                item.setProduct(p);
                if (item.getUnitCost() == null) {
                    item.setUnitCost(p.getCostPrice());
                }
                orderItemRepository.save(item);
                log.info("[BACKFILL] Item #{} → produit '{}' (SKU: {}) unitCost={}",
                        item.getId(), p.getName(), sku, item.getUnitCost());
                matched++;
                continue;
            }

            // 3. SKU non trouvé
            unmatched++;
            unmatchedDetails.add(
                    "Item #" + item.getId()
                    + " (commande " + item.getOrder().getOrderNumber() + ")"
                    + " — '" + item.getProductName() + "' SKU='" + sku + "' : introuvable en base");
            log.warn("[BACKFILL] SKU '{}' introuvable — item #{} '{}'", sku, item.getId(), item.getProductName());
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

    private BigDecimal resolveVariantCost(ProductVariant v) {
        if (v.getCostPrice() != null) return v.getCostPrice();
        if (v.getProduct() != null && v.getProduct().getCostPrice() != null) return v.getProduct().getCostPrice();
        return null;
    }
}
