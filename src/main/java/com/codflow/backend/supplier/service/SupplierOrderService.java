package com.codflow.backend.supplier.service;

import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.product.entity.ProductVariant;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.product.repository.ProductVariantRepository;
import com.codflow.backend.stock.entity.StockMovement;
import com.codflow.backend.stock.enums.MovementType;
import com.codflow.backend.stock.repository.StockMovementRepository;
import com.codflow.backend.supplier.dto.*;
import com.codflow.backend.supplier.entity.*;
import com.codflow.backend.supplier.enums.SupplierOrderStatus;
import com.codflow.backend.supplier.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierOrderService {

    private final SupplierOrderRepository supplierOrderRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final SupplierDeliveryRepository supplierDeliveryRepository;
    private final SupplierService supplierService;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final StockMovementRepository stockMovementRepository;

    // -------------------------------------------------------------------------
    // Commandes fournisseur
    // -------------------------------------------------------------------------

    @Transactional
    public SupplierOrderDto createOrder(CreateSupplierOrderRequest request) {
        Supplier supplier = supplierService.getSupplierById(request.getSupplierId());

        SupplierOrder order = new SupplierOrder();
        order.setOrderNumber(generateOrderNumber());
        order.setSupplier(supplier);
        order.setOrderDate(request.getOrderDate());
        order.setExpectedDeliveryDate(request.getExpectedDeliveryDate());
        order.setNotes(request.getNotes());
        order.setStatus(SupplierOrderStatus.BROUILLON);

        for (CreateSupplierOrderRequest.OrderItemRequest itemReq : request.getItems()) {
            SupplierOrderItem item = new SupplierOrderItem();
            item.setSupplierOrder(order);
            item.setProductName(itemReq.getProductName());
            item.setProductSku(itemReq.getProductSku());
            item.setQuantityOrdered(itemReq.getQuantityOrdered());
            item.setUnitCost(itemReq.getUnitCost());
            item.calculateTotalCost();
            if (itemReq.getProductId() != null) {
                productRepository.findById(itemReq.getProductId()).ifPresent(item::setProduct);
            }
            if (itemReq.getVariantId() != null) {
                productVariantRepository.findById(itemReq.getVariantId()).ifPresent(item::setVariant);
            }
            order.getItems().add(item);
        }
        order.recalculateTotalAmount();

        return toDto(supplierOrderRepository.save(order));
    }

    @Transactional
    public SupplierOrderDto confirmOrder(Long orderId) {
        SupplierOrder order = getOrderById(orderId);
        if (order.getStatus() != SupplierOrderStatus.BROUILLON) {
            throw new BusinessException("Seul un bon en statut BROUILLON peut être confirmé");
        }
        order.setStatus(SupplierOrderStatus.CONFIRME);
        return toDto(supplierOrderRepository.save(order));
    }

    @Transactional
    public SupplierOrderDto cancelOrder(Long orderId) {
        SupplierOrder order = getOrderById(orderId);
        if (order.getStatus() == SupplierOrderStatus.COMPLETE) {
            throw new BusinessException("Un bon de commande complété ne peut pas être annulé");
        }
        order.setStatus(SupplierOrderStatus.ANNULE);
        return toDto(supplierOrderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public SupplierOrderDto getOrder(Long id) {
        return toDto(getOrderById(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierOrderDto> getOrders(Long supplierId, SupplierOrderStatus status, Pageable pageable) {
        Page<SupplierOrder> page;
        if (supplierId != null) {
            page = supplierOrderRepository.findBySupplierId(supplierId, pageable);
        } else if (status != null) {
            page = supplierOrderRepository.findByStatus(status, pageable);
        } else {
            page = supplierOrderRepository.findAll(pageable);
        }
        return PageResponse.of(page.map(this::toDto));
    }

    // -------------------------------------------------------------------------
    // Paiements
    // -------------------------------------------------------------------------

    @Transactional
    public SupplierOrderDto recordPayment(Long orderId, RecordPaymentRequest request) {
        SupplierOrder order = getOrderById(orderId);
        if (order.getStatus() == SupplierOrderStatus.ANNULE) {
            throw new BusinessException("Impossible d'enregistrer un paiement sur un bon annulé");
        }
        if (order.getStatus() == SupplierOrderStatus.BROUILLON) {
            throw new BusinessException("Confirmez le bon de commande avant d'enregistrer un paiement");
        }

        BigDecimal newTotal = order.getPaidAmount().add(request.getAmount());
        if (newTotal.compareTo(order.getTotalAmount()) > 0) {
            throw new BusinessException(
                    "Le montant payé (" + newTotal + " DH) dépasse le total de la commande (" + order.getTotalAmount() + " DH)");
        }

        SupplierPayment payment = new SupplierPayment();
        payment.setSupplierOrder(order);
        payment.setAmount(request.getAmount());
        payment.setPaymentDate(request.getPaymentDate());
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setReference(request.getReference());
        payment.setNotes(request.getNotes());

        order.setPaidAmount(newTotal);
        order.getPayments().add(payment);
        supplierOrderRepository.save(order);

        log.info("[SUPPLIER-PAYMENT] Bon {} — paiement de {} DH enregistré", order.getOrderNumber(), request.getAmount());
        return toDto(order);
    }

    @Transactional
    public void deletePayment(Long orderId, Long paymentId) {
        SupplierOrder order = getOrderById(orderId);
        SupplierPayment payment = order.getPayments().stream()
                .filter(p -> p.getId().equals(paymentId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Paiement", paymentId));

        order.setPaidAmount(order.getPaidAmount().subtract(payment.getAmount()));
        order.getPayments().remove(payment);
        supplierOrderRepository.save(order);
    }

    // -------------------------------------------------------------------------
    // Réceptions (lots)
    // -------------------------------------------------------------------------

    @Transactional
    public SupplierOrderDto recordDelivery(Long orderId, RecordDeliveryRequest request) {
        SupplierOrder order = getOrderById(orderId);
        if (order.getStatus() == SupplierOrderStatus.BROUILLON) {
            throw new BusinessException("Confirmez le bon de commande avant d'enregistrer une réception");
        }
        if (order.getStatus() == SupplierOrderStatus.ANNULE) {
            throw new BusinessException("Impossible d'enregistrer une réception sur un bon annulé");
        }
        if (order.getStatus() == SupplierOrderStatus.COMPLETE) {
            throw new BusinessException("Ce bon est déjà complètement reçu");
        }

        // Index des lignes de commande par ID
        Map<Long, SupplierOrderItem> itemsById = order.getItems().stream()
                .collect(Collectors.toMap(SupplierOrderItem::getId, Function.identity()));

        SupplierDelivery delivery = new SupplierDelivery();
        delivery.setSupplierOrder(order);
        delivery.setLotNumber(request.getLotNumber());
        delivery.setDeliveryDate(request.getDeliveryDate());
        delivery.setNotes(request.getNotes());

        for (RecordDeliveryRequest.DeliveryItemRequest itemReq : request.getItems()) {
            SupplierOrderItem orderItem = itemsById.get(itemReq.getOrderItemId());
            if (orderItem == null) {
                throw new BusinessException("Ligne de commande introuvable : id=" + itemReq.getOrderItemId());
            }
            int remaining = orderItem.getRemainingQuantity();
            if (itemReq.getQuantityReceived() > remaining) {
                throw new BusinessException(
                        "Quantité reçue (" + itemReq.getQuantityReceived() + ") dépasse le reliquat (" + remaining + ") pour : " + orderItem.getProductName());
            }

            BigDecimal effectiveUnitCost = itemReq.getUnitCost() != null
                    ? itemReq.getUnitCost()
                    : orderItem.getUnitCost();

            SupplierDeliveryItem deliveryItem = new SupplierDeliveryItem();
            deliveryItem.setDelivery(delivery);
            deliveryItem.setOrderItem(orderItem);
            deliveryItem.setQuantityReceived(itemReq.getQuantityReceived());
            deliveryItem.setUnitCost(effectiveUnitCost);
            delivery.getItems().add(deliveryItem);

            // Mise à jour quantité reçue sur la ligne
            orderItem.setQuantityReceived(orderItem.getQuantityReceived() + itemReq.getQuantityReceived());

            // Injection en stock + CMUP
            if (orderItem.getVariant() != null) {
                updateVariantStockAndCost(orderItem.getVariant(), itemReq.getQuantityReceived(),
                        effectiveUnitCost, order.getOrderNumber());
            }
        }

        order.getDeliveries().add(delivery);

        // Mise à jour statut : EN_COURS ou COMPLETE
        boolean allReceived = order.getItems().stream().allMatch(SupplierOrderItem::isFullyReceived);
        order.setStatus(allReceived ? SupplierOrderStatus.COMPLETE : SupplierOrderStatus.EN_COURS);

        supplierOrderRepository.save(order);
        log.info("[SUPPLIER-DELIVERY] Bon {} — réception lot '{}' enregistrée, statut → {}",
                order.getOrderNumber(), request.getLotNumber(), order.getStatus());
        return toDto(order);
    }

    /**
     * Injecte le stock reçu dans le variant et recalcule le CMUP.
     * Utilise les @Modifying directs (pattern StockArrivalService) pour éviter
     * les problèmes de cache L1 Hibernate.
     * Crée également un StockMovement pour la traçabilité.
     */
    private void updateVariantStockAndCost(ProductVariant variant, int qtyReceived,
                                           BigDecimal unitCost, String orderNumber) {
        // Capturer les valeurs AVANT tout @Modifying (qui vide le L1 cache)
        int prevVariantStock  = variant.getCurrentStock();
        int newVariantStock   = prevVariantStock + qtyReceived;
        BigDecimal prevCost   = variant.getCostPrice() != null ? variant.getCostPrice() : BigDecimal.ZERO;

        // CMUP = (stock_actuel × coût_actuel + qty_reçue × coût_lot) / nouveau_stock
        BigDecimal newCmup;
        if (prevVariantStock > 0 && prevCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal totalValue = prevCost.multiply(BigDecimal.valueOf(prevVariantStock))
                    .add(unitCost.multiply(BigDecimal.valueOf(qtyReceived)));
            newCmup = totalValue.divide(BigDecimal.valueOf(newVariantStock), 4, RoundingMode.HALF_UP);
        } else {
            newCmup = unitCost;
        }

        // Mettre à jour le stock variant (direct UPDATE — safe vis-à-vis du cache L1)
        productVariantRepository.updateCurrentStock(variant.getId(), newVariantStock);
        // Mettre à jour le CMUP (direct UPDATE après le premier pour éviter la réouverture de session)
        productVariantRepository.updateCostPrice(variant.getId(), newCmup);

        // Mettre à jour le stock produit parent
        if (variant.getProduct() != null) {
            int prevProductStock = variant.getProduct().getCurrentStock();
            int newProductStock  = prevProductStock + qtyReceived;
            productRepository.updateCurrentStock(variant.getProduct().getId(), newProductStock);

            // StockMovement pour traçabilité
            StockMovement movement = new StockMovement();
            movement.setProduct(variant.getProduct());
            movement.setMovementType(MovementType.IN);
            movement.setQuantity(qtyReceived);
            movement.setPreviousStock(prevProductStock);
            movement.setNewStock(newProductStock);
            movement.setReason("Réception fournisseur — bon " + orderNumber);
            movement.setReferenceType("SUPPLIER_DELIVERY");
            stockMovementRepository.save(movement);
        }

        log.info("[SUPPLIER-STOCK] Variant {} — stock {} → {}, CMUP {} → {}",
                variant.getVariantSku(), prevVariantStock, newVariantStock, prevCost, newCmup);
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private SupplierOrder getOrderById(Long id) {
        return supplierOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bon de commande fournisseur", id));
    }

    private String generateOrderNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "FRN-" + date + "-";
        long count = supplierOrderRepository.count() + 1;
        String suffix = String.format("%06d", count);
        String candidate = prefix + suffix;
        // Collision avoidance
        while (supplierOrderRepository.existsByOrderNumber(candidate)) {
            suffix = String.format("%06d", ++count);
            candidate = prefix + suffix;
        }
        return candidate;
    }

    public SupplierOrderDto toDto(SupplierOrder order) {
        List<SupplierOrderItemDto> items = order.getItems().stream().map(i ->
                SupplierOrderItemDto.builder()
                        .id(i.getId())
                        .productId(i.getProduct() != null ? i.getProduct().getId() : null)
                        .variantId(i.getVariant() != null ? i.getVariant().getId() : null)
                        .productName(i.getProductName())
                        .productSku(i.getProductSku())
                        .quantityOrdered(i.getQuantityOrdered())
                        .quantityReceived(i.getQuantityReceived())
                        .remainingQuantity(i.getRemainingQuantity())
                        .unitCost(i.getUnitCost())
                        .totalCost(i.getTotalCost())
                        .build()
        ).toList();

        List<SupplierPaymentDto> payments = order.getPayments().stream().map(p ->
                SupplierPaymentDto.builder()
                        .id(p.getId())
                        .amount(p.getAmount())
                        .paymentDate(p.getPaymentDate())
                        .paymentMethod(p.getPaymentMethod())
                        .paymentMethodLabel(p.getPaymentMethod().getLabel())
                        .reference(p.getReference())
                        .notes(p.getNotes())
                        .createdAt(p.getCreatedAt())
                        .build()
        ).toList();

        List<SupplierDeliveryDto> deliveries = order.getDeliveries().stream().map(d -> {
            List<SupplierDeliveryItemDto> dItems = d.getItems().stream().map(di ->
                    SupplierDeliveryItemDto.builder()
                            .id(di.getId())
                            .orderItemId(di.getOrderItem().getId())
                            .productName(di.getOrderItem().getProductName())
                            .productSku(di.getOrderItem().getProductSku())
                            .quantityReceived(di.getQuantityReceived())
                            .unitCost(di.getUnitCost())
                            .build()
            ).toList();
            return SupplierDeliveryDto.builder()
                    .id(d.getId())
                    .lotNumber(d.getLotNumber())
                    .deliveryDate(d.getDeliveryDate())
                    .notes(d.getNotes())
                    .items(dItems)
                    .createdAt(d.getCreatedAt())
                    .build();
        }).toList();

        return SupplierOrderDto.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .supplierId(order.getSupplier().getId())
                .supplierName(order.getSupplier().getName())
                .status(order.getStatus())
                .statusLabel(order.getStatus().getLabel())
                .orderDate(order.getOrderDate())
                .expectedDeliveryDate(order.getExpectedDeliveryDate())
                .totalAmount(order.getTotalAmount())
                .paidAmount(order.getPaidAmount())
                .remainingAmount(order.getRemainingAmount())
                .notes(order.getNotes())
                .items(items)
                .payments(payments)
                .deliveries(deliveries)
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
