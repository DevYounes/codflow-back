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
import java.util.List;

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

    @Transactional
    public void deductStockForOrder(Long productId, int quantity, Long orderId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Produit", productId));

        int previousStock = product.getCurrentStock();
        int newStock = Math.max(0, previousStock - quantity);

        productRepository.updateCurrentStock(product.getId(), newStock);
        product.setCurrentStock(newStock);

        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(MovementType.OUT);
        movement.setQuantity(quantity);
        movement.setPreviousStock(previousStock);
        movement.setNewStock(newStock);
        movement.setReason("Commande confirmée");
        movement.setReferenceType("ORDER");
        movement.setReferenceId(orderId);
        stockMovementRepository.save(movement);

        checkAndCreateAlerts(product);
    }

    @Transactional
    public void restoreStockForOrder(Long productId, int quantity, Long orderId, String reason) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Produit", productId));

        int previousStock = product.getCurrentStock();
        int newStock = previousStock + quantity;

        productRepository.updateCurrentStock(product.getId(), newStock);
        product.setCurrentStock(newStock);

        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(MovementType.RETURN);
        movement.setQuantity(quantity);
        movement.setPreviousStock(previousStock);
        movement.setNewStock(newStock);
        movement.setReason(reason != null ? reason : "Retour commande");
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
        List<Product> lowStock = productRepository.findLowStockProducts();
        lowStock.forEach(this::checkAndCreateAlerts);
    }

    private void checkAndCreateAlerts(Product product) {
        if (!product.isAlertEnabled()) return;

        if (product.getCurrentStock() == 0) {
            createAlertIfNotExists(product, AlertType.OUT_OF_STOCK);
        } else if (product.getCurrentStock() <= product.getMinThreshold()) {
            createAlertIfNotExists(product, AlertType.LOW_STOCK);
        }
    }

    private void createAlertIfNotExists(Product product, AlertType alertType) {
        boolean exists = stockAlertRepository.existsByProductIdAndAlertTypeAndResolvedFalse(product.getId(), alertType);
        if (!exists) {
            StockAlert alert = new StockAlert();
            alert.setProduct(product);
            alert.setAlertType(alertType);
            alert.setThreshold(product.getMinThreshold());
            alert.setCurrentLevel(product.getCurrentStock());
            stockAlertRepository.save(alert);
            log.warn("Stock alert created: {} for product {} (stock: {})", alertType, product.getSku(), product.getCurrentStock());
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
        return StockAlertDto.builder()
                .id(a.getId())
                .productId(a.getProduct().getId())
                .productName(a.getProduct().getName())
                .productSku(a.getProduct().getSku())
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
