package com.codflow.backend.stock.service;

import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.product.entity.Product;
import com.codflow.backend.product.entity.ProductVariant;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.product.repository.ProductVariantRepository;
import com.codflow.backend.security.UserPrincipal;
import com.codflow.backend.stock.dto.AdjustStockRequest;
import com.codflow.backend.stock.dto.StockAlertDto;
import com.codflow.backend.stock.dto.StockMovementDto;
import com.codflow.backend.stock.entity.StockAlert;
import com.codflow.backend.stock.entity.StockMovement;
import com.codflow.backend.stock.enums.AlertType;
import com.codflow.backend.stock.enums.MovementType;
import com.codflow.backend.stock.repository.StockAlertRepository;
import com.codflow.backend.stock.repository.StockMovementRepository;
import com.codflow.backend.team.entity.User;
import com.codflow.backend.team.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockAlertRepository stockAlertRepository;
    private final UserRepository userRepository;

    @Transactional
    public StockMovementDto adjustStock(AdjustStockRequest request, UserPrincipal principal) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Produit", request.getProductId()));

        // --- Variant-level adjustment ---
        if (request.getVariantId() != null) {
            ProductVariant variant = variantRepository.findById(request.getVariantId())
                    .orElseThrow(() -> new ResourceNotFoundException("Variante", request.getVariantId()));
            if (!variant.getProduct().getId().equals(product.getId())) {
                throw new BusinessException("La variante n'appartient pas à ce produit");
            }

            int prevVariant = variant.getCurrentStock();
            int newVariant = computeNewStock(prevVariant, request);
            variantRepository.updateCurrentStock(variant.getId(), newVariant);

            // Also update product aggregate
            int diff = newVariant - prevVariant;
            int newProductStock = Math.max(0, product.getCurrentStock() + diff);
            productRepository.updateCurrentStock(product.getId(), newProductStock);
            product.setCurrentStock(newProductStock);

            StockMovement movement = buildMovement(product, request, prevVariant, newVariant, principal);
            stockMovementRepository.save(movement);
            checkAndCreateAlerts(product);
            return toMovementDto(movement);
        }

        // --- Product-level adjustment (no variant) ---
        int previousStock = product.getCurrentStock();
        int newStock = computeNewStock(previousStock, request);
        productRepository.updateCurrentStock(product.getId(), newStock);
        product.setCurrentStock(newStock);

        StockMovement movement = buildMovement(product, request, previousStock, newStock, principal);
        stockMovementRepository.save(movement);
        checkAndCreateAlerts(product);
        return toMovementDto(movement);
    }

    private int computeNewStock(int current, AdjustStockRequest request) {
        return switch (request.getMovementType()) {
            case IN, RETURN -> current + request.getQuantity();
            case OUT -> {
                if (current < request.getQuantity())
                    throw new BusinessException("Stock insuffisant. Stock actuel: " + current);
                yield current - request.getQuantity();
            }
            default -> request.getQuantity(); // ADJUSTMENT = set absolute value
        };
    }

    private StockMovement buildMovement(Product product, AdjustStockRequest request,
                                         int previousStock, int newStock, UserPrincipal principal) {
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(request.getMovementType());
        movement.setQuantity(request.getQuantity());
        movement.setPreviousStock(previousStock);
        movement.setNewStock(newStock);
        movement.setReason(request.getReason());
        if (principal != null) {
            userRepository.findById(principal.getId()).ifPresent(movement::setCreatedBy);
        }
        return movement;
    }

    /**
     * CONFIRME → réserve le stock (reservedStock++, currentStock inchangé).
     * availableStock = currentStock - reservedStock diminue.
     */
    @Transactional
    public void reserveStockForOrder(Long productId, Long variantId, int quantity, Long orderId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Produit", productId));

        if (variantId != null) {
            variantRepository.findById(variantId).ifPresent(v -> {
                v.setReservedStock(v.getReservedStock() + quantity);
                variantRepository.save(v);
            });
        }

        int previousStock = product.getCurrentStock();
        product.setReservedStock(product.getReservedStock() + quantity);
        productRepository.save(product);

        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(MovementType.RESERVE);
        movement.setQuantity(quantity);
        movement.setPreviousStock(previousStock);
        movement.setNewStock(previousStock); // currentStock unchanged
        movement.setReason("Réservation commande confirmée");
        movement.setReferenceType("ORDER");
        movement.setReferenceId(orderId);
        stockMovementRepository.save(movement);
    }

    /**
     * LIVRE → finalise la déduction (currentStock--, reservedStock--).
     * Le stock est définitivement sorti.
     */
    @Transactional
    public void finalizeStockDeduction(Long productId, Long variantId, int quantity, Long orderId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Produit", productId));

        if (variantId != null) {
            variantRepository.findById(variantId).ifPresent(v -> {
                v.setCurrentStock(Math.max(0, v.getCurrentStock() - quantity));
                v.setReservedStock(Math.max(0, v.getReservedStock() - quantity));
                variantRepository.save(v);
            });
        }

        int previousStock = product.getCurrentStock();
        int newStock = Math.max(0, previousStock - quantity);
        product.setCurrentStock(newStock);
        product.setReservedStock(Math.max(0, product.getReservedStock() - quantity));
        productRepository.save(product);

        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(MovementType.OUT);
        movement.setQuantity(quantity);
        movement.setPreviousStock(previousStock);
        movement.setNewStock(newStock);
        movement.setReason("Commande livrée");
        movement.setReferenceType("ORDER");
        movement.setReferenceId(orderId);
        stockMovementRepository.save(movement);

        checkAndCreateAlerts(product);
    }

    /**
     * RETOURNE/ANNULÉ avant livraison → libère la réservation (reservedStock--, currentStock inchangé).
     */
    @Transactional
    public void releaseReservation(Long productId, Long variantId, int quantity, Long orderId, String reason) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Produit", productId));

        if (variantId != null) {
            variantRepository.findById(variantId).ifPresent(v -> {
                v.setReservedStock(Math.max(0, v.getReservedStock() - quantity));
                variantRepository.save(v);
            });
        }

        int currentStock = product.getCurrentStock();
        product.setReservedStock(Math.max(0, product.getReservedStock() - quantity));
        productRepository.save(product);

        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(MovementType.RESERVE_RELEASE);
        movement.setQuantity(quantity);
        movement.setPreviousStock(currentStock);
        movement.setNewStock(currentStock); // currentStock unchanged
        movement.setReason(reason != null ? reason : "Libération de réservation");
        movement.setReferenceType("ORDER");
        movement.setReferenceId(orderId);
        stockMovementRepository.save(movement);
    }

    /**
     * RETOURNE après livraison → restaure le stock physique (currentStock++).
     */
    @Transactional
    public void restoreStockForOrder(Long productId, Long variantId, int quantity, Long orderId, String reason) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Produit", productId));

        if (variantId != null) {
            variantRepository.findById(variantId).ifPresent(v -> {
                v.setCurrentStock(v.getCurrentStock() + quantity);
                variantRepository.save(v);
            });
        }

        int previousStock = product.getCurrentStock();
        int newStock = previousStock + quantity;
        product.setCurrentStock(newStock);
        productRepository.save(product);

        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(MovementType.RETURN);
        movement.setQuantity(quantity);
        movement.setPreviousStock(previousStock);
        movement.setNewStock(newStock);
        movement.setReason(reason != null ? reason : "Retour commande après livraison");
        movement.setReferenceType("ORDER");
        movement.setReferenceId(orderId);
        stockMovementRepository.save(movement);
    }

    @Transactional(readOnly = true)
    public PageResponse<StockMovementDto> getMovements(Long productId, Pageable pageable) {
        Page<StockMovement> page = productId != null
                ? stockMovementRepository.findByProductId(productId, pageable)
                : stockMovementRepository.findAll(pageable);
        return PageResponse.of(page.map(this::toMovementDto));
    }

    @Transactional(readOnly = true)
    public List<StockAlertDto> getActiveAlerts() {
        return stockAlertRepository.findByResolvedFalse()
                .stream().map(this::toAlertDto).toList();
    }

    @Transactional
    public void resolveAlert(Long alertId, UserPrincipal principal) {
        StockAlert alert = stockAlertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alerte", alertId));
        alert.setResolved(true);
        alert.setResolvedAt(LocalDateTime.now());
        if (principal != null) {
            userRepository.findById(principal.getId()).ifPresent(alert::setResolvedBy);
        }
        stockAlertRepository.save(alert);
    }

    @Scheduled(fixedRateString = "${app.stock.alert-check-interval-ms}")
    @Transactional
    public void checkAllStockAlerts() {
        log.debug("Running scheduled stock alert check...");
        Set<Long> checked = new HashSet<>();
        // Products with low aggregate stock
        productRepository.findLowStockProducts().forEach(p -> {
            checked.add(p.getId());
            checkAndCreateAlerts(p);
        });
        // Products with at least one low-stock variant (may not be in the aggregate list)
        variantRepository.findProductsWithLowStockVariants().forEach(p -> {
            if (checked.add(p.getId())) {
                checkAndCreateAlerts(p);
            }
        });
    }

    private void checkAndCreateAlerts(Product product) {
        if (!product.isAlertEnabled()) return;

        List<ProductVariant> variants = variantRepository.findByProductIdAndActiveTrue(product.getId());

        if (!variants.isEmpty()) {
            // Per-variant alerts
            for (ProductVariant variant : variants) {
                if (variant.getCurrentStock() == 0) {
                    createAlertIfNotExists(product, variant, AlertType.OUT_OF_STOCK);
                } else if (variant.getCurrentStock() <= product.getMinThreshold()) {
                    createAlertIfNotExists(product, variant, AlertType.LOW_STOCK);
                }
            }
        } else {
            // Product-level alert (no variants)
            if (product.getCurrentStock() == 0) {
                createAlertIfNotExists(product, null, AlertType.OUT_OF_STOCK);
            } else if (product.getCurrentStock() <= product.getMinThreshold()) {
                createAlertIfNotExists(product, null, AlertType.LOW_STOCK);
            }
        }
    }

    private void createAlertIfNotExists(Product product, ProductVariant variant, AlertType alertType) {
        boolean exists;
        int currentLevel;
        String label;
        if (variant != null) {
            exists = stockAlertRepository.existsByProductIdAndVariantIdAndAlertTypeAndResolvedFalse(
                    product.getId(), variant.getId(), alertType);
            currentLevel = variant.getCurrentStock();
            label = product.getSku() + " / " + variant.getColor() + " " + variant.getSize();
        } else {
            exists = stockAlertRepository.existsByProductIdAndVariantIsNullAndAlertTypeAndResolvedFalse(
                    product.getId(), alertType);
            currentLevel = product.getCurrentStock();
            label = product.getSku();
        }
        if (!exists) {
            StockAlert alert = new StockAlert();
            alert.setProduct(product);
            alert.setVariant(variant);
            alert.setAlertType(alertType);
            alert.setThreshold(product.getMinThreshold());
            alert.setCurrentLevel(currentLevel);
            stockAlertRepository.save(alert);
            log.warn("Stock alert created: {} for {} (stock: {})", alertType, label, currentLevel);
        }
    }

    private StockMovementDto toMovementDto(StockMovement m) {
        return StockMovementDto.builder()
                .id(m.getId())
                .productId(m.getProduct().getId())
                .productName(m.getProduct().getName())
                .productSku(m.getProduct().getSku())
                .movementType(m.getMovementType())
                .quantity(m.getQuantity())
                .previousStock(m.getPreviousStock())
                .newStock(m.getNewStock())
                .reason(m.getReason())
                .referenceType(m.getReferenceType())
                .referenceId(m.getReferenceId())
                .createdByName(m.getCreatedBy() != null ? m.getCreatedBy().getFullName() : null)
                .createdAt(m.getCreatedAt())
                .build();
    }

    private StockAlertDto toAlertDto(StockAlert a) {
        ProductVariant v = a.getVariant();
        String variantLabel = null;
        if (v != null) {
            variantLabel = (v.getColor() != null ? v.getColor() : "")
                    + (v.getColor() != null && v.getSize() != null ? " / " : "")
                    + (v.getSize() != null ? v.getSize() : "");
        }
        return StockAlertDto.builder()
                .id(a.getId())
                .productId(a.getProduct().getId())
                .productName(a.getProduct().getName())
                .productSku(a.getProduct().getSku())
                .variantId(v != null ? v.getId() : null)
                .variantSku(v != null ? v.getVariantSku() : null)
                .variantLabel(variantLabel)
                .alertType(a.getAlertType())
                .threshold(a.getThreshold())
                .currentLevel(a.getCurrentLevel())
                .resolved(a.isResolved())
                .resolvedAt(a.getResolvedAt())
                .resolvedByName(a.getResolvedBy() != null ? a.getResolvedBy().getFullName() : null)
                .createdAt(a.getCreatedAt())
                .build();
    }
}
