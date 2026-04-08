package com.codflow.backend.order.service;

import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.common.util.PhoneNormalizer;
import com.codflow.backend.customer.entity.Customer;
import com.codflow.backend.customer.repository.CustomerRepository;
import com.codflow.backend.delivery.entity.DeliveryShipment;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import com.codflow.backend.order.dto.*;
import com.codflow.backend.order.entity.Order;
import com.codflow.backend.order.entity.OrderItem;
import com.codflow.backend.order.entity.OrderStatusHistory;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.enums.OrderStatus;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.order.repository.OrderSpecification;
import com.codflow.backend.order.repository.OrderStatusHistoryRepository;
import com.codflow.backend.product.entity.ProductVariant;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.product.repository.ProductVariantRepository;
import com.codflow.backend.security.UserPrincipal;
import com.codflow.backend.stock.service.StockService;
import com.codflow.backend.team.entity.User;
import com.codflow.backend.team.enums.Role;
import com.codflow.backend.team.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final DeliveryShipmentRepository shipmentRepository;
    private final StockService stockService;
    private final RoundRobinAssignmentService roundRobinAssignmentService;
    private final CustomerRepository customerRepository;

    @Transactional
    public OrderDto createOrder(CreateOrderRequest request, UserPrincipal principal) {
        String orderNumber = generateOrderNumber(request);

        Order order = new Order();
        order.setOrderNumber(orderNumber);
        order.setSource(request.getSource() != null ? request.getSource() : OrderSource.MANUAL);
        order.setCustomerName(request.getCustomerName());
        // Normalize phone for duplicate detection across format variants
        // (0612345678 / 212612345678 / +212612345678 all resolve to the same canonical form)
        String normalizedPhone = PhoneNormalizer.normalize(request.getCustomerPhone());
        order.setCustomerPhone(request.getCustomerPhone());
        order.setCustomerPhoneNormalized(normalizedPhone);
        order.setCustomerPhone2(request.getCustomerPhone2());
        order.setAddress(request.getAddress());
        order.setVille(request.getVille());
        // city (NOT NULL in DB) is derived from ville when not explicitly provided
        order.setCity(request.getCity() != null && !request.getCity().isBlank()
                ? request.getCity() : request.getVille());

        // Flag as potential duplicate if another order already exists for this phone number
        if (normalizedPhone != null && orderRepository.existsByCustomerPhoneNormalized(normalizedPhone)) {
            order.setPotentialDuplicate(true);
        }

        // Link or create customer
        Customer customer = findOrCreateCustomer(
                request.getCustomerPhone(), normalizedPhone,
                request.getCustomerName(), request.getAddress(), request.getVille());
        order.setCustomer(customer);

        order.setZipCode(request.getZipCode());
        order.setNotes(request.getNotes());
        order.setShippingCost(request.getShippingCost() != null ? request.getShippingCost() : java.math.BigDecimal.ZERO);
        order.setShopifyOrderId(request.getShopifyOrderId());
        order.setExternalRef(request.getExternalRef());
        order.setStatus(OrderStatus.NOUVEAU);

        // Add items
        for (CreateOrderRequest.OrderItemRequest itemReq : request.getItems()) {
            OrderItem item = new OrderItem();
            item.setProductName(itemReq.getProductName());
            item.setProductSku(itemReq.getProductSku());
            item.setQuantity(itemReq.getQuantity());
            item.setUnitPrice(itemReq.getUnitPrice());

            if (itemReq.getProductId() != null) {
                productRepository.findById(itemReq.getProductId()).ifPresent(item::setProduct);
            }
            if (itemReq.getVariantId() != null) {
                productVariantRepository.findById(itemReq.getVariantId()).ifPresent(v -> {
                    item.setVariant(v);
                    if (v.getPriceOverride() != null && itemReq.getUnitPrice() == null) {
                        item.setUnitPrice(v.getPriceOverride());
                    }
                });
            }
            item.calculateTotalPrice();
            order.addItem(item);
        }
        order.recalculateTotals();

        Order saved = orderRepository.save(order);

        // Record initial status history
        recordStatusChange(saved, null, OrderStatus.NOUVEAU, principal, "Commande créée");

        // Auto-assign via round-robin
        roundRobinAssignmentService.assign(saved);

        log.info("Order created: {} from source {}", saved.getOrderNumber(), saved.getSource());
        return toDto(saved, true);
    }

    /**
     * Crée une commande d'échange à partir d'une commande livrée.
     *
     * Comportement :
     * - Copie le client, l'adresse, la ville et le deliveryCityId de la commande source
     * - Les articles sont fournis dans la requête (nouveau produit à envoyer)
     * - La commande est créée directement en statut CONFIRME (échange déjà validé par le client)
     * - Le flag isExchange = true est transmis à la société de livraison (parcel-echange=1 chez Ozon)
     * - Le stock est réservé immédiatement
     */
    @Transactional
    public OrderDto createExchangeOrder(Long sourceOrderId, CreateOrderRequest request, UserPrincipal principal) {
        Order sourceOrder = getOrderById(sourceOrderId);

        if (sourceOrder.getStatus() != OrderStatus.LIVRE) {
            throw new BusinessException(
                    "L'échange ne peut être créé que pour une commande livrée (statut actuel: "
                    + sourceOrder.getStatus().getLabel() + ")");
        }

        // Construire la commande d'échange en copiant les infos client de la source
        Order exchange = new Order();
        exchange.setSource(OrderSource.MANUAL);
        exchange.setExchange(true);
        exchange.setSourceOrder(sourceOrder);

        // Client — peut être surchargé par la requête mais par défaut = même client
        exchange.setCustomerName(firstNonBlank(request.getCustomerName(), sourceOrder.getCustomerName()));
        String rawPhone = firstNonBlank(request.getCustomerPhone(), sourceOrder.getCustomerPhone());
        String normalizedPhone = com.codflow.backend.common.util.PhoneNormalizer.normalize(rawPhone);
        exchange.setCustomerPhone(rawPhone);
        exchange.setCustomerPhoneNormalized(normalizedPhone);
        exchange.setCustomerPhone2(request.getCustomerPhone2() != null
                ? request.getCustomerPhone2() : sourceOrder.getCustomerPhone2());

        // Adresse — par défaut = même adresse
        exchange.setAddress(firstNonBlank(request.getAddress(), sourceOrder.getAddress()));
        exchange.setVille(firstNonBlank(request.getVille(), sourceOrder.getVille()));
        exchange.setCity(firstNonBlank(request.getCity(), sourceOrder.getCity()));
        exchange.setDeliveryCityId(request.getDeliveryCityId() != null && !request.getDeliveryCityId().isBlank()
                ? request.getDeliveryCityId() : sourceOrder.getDeliveryCityId());
        exchange.setZipCode(request.getZipCode() != null ? request.getZipCode() : sourceOrder.getZipCode());
        exchange.setNotes(request.getNotes());
        exchange.setShippingCost(request.getShippingCost() != null
                ? request.getShippingCost() : java.math.BigDecimal.ZERO);

        // Numéro de commande
        String exchangeNumber = "ECHANGE-" + sourceOrder.getOrderNumber();
        if (orderRepository.existsByOrderNumber(exchangeNumber)) {
            exchangeNumber = "ECHANGE-" + sourceOrder.getOrderNumber() + "-"
                    + java.util.UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        }
        exchange.setOrderNumber(exchangeNumber);

        // Lier au customer existant
        exchange.setCustomer(sourceOrder.getCustomer());

        // Assignation : request.assignedToId > créateur (principal) > assigné de la commande source
        Long assignedToId = request.getAssignedToId() != null
                ? request.getAssignedToId()
                : principal.getId();
        userRepository.findById(assignedToId).ifPresent(exchange::setAssignedTo);

        // Articles du nouvel échange
        for (CreateOrderRequest.OrderItemRequest itemReq : request.getItems()) {
            OrderItem item = new OrderItem();
            item.setProductName(itemReq.getProductName());
            item.setProductSku(itemReq.getProductSku());
            item.setQuantity(itemReq.getQuantity());
            item.setUnitPrice(itemReq.getUnitPrice());
            if (itemReq.getProductId() != null) {
                productRepository.findById(itemReq.getProductId()).ifPresent(item::setProduct);
            }
            if (itemReq.getVariantId() != null) {
                productVariantRepository.findById(itemReq.getVariantId()).ifPresent(v -> {
                    item.setVariant(v);
                    if (v.getPriceOverride() != null && itemReq.getUnitPrice() == null) {
                        item.setUnitPrice(v.getPriceOverride());
                    }
                });
            }
            item.calculateTotalPrice();
            exchange.addItem(item);
        }
        exchange.recalculateTotals();

        // Démarrer directement en CONFIRME (échange déjà validé)
        exchange.setStatus(OrderStatus.CONFIRME);
        exchange.setConfirmedAt(LocalDateTime.now());

        Order saved = orderRepository.save(exchange);

        // Snapshot coûts + réservation stock AVANT tout accès DB
        // (les items sont encore en mémoire avec leurs références produit)
        snapshotUnitCosts(saved);
        reserveStockForOrder(saved);
        saved.setStockReserved(true);

        // Historique : NOUVEAU → CONFIRME
        recordStatusChange(saved, null, OrderStatus.NOUVEAU, principal, "Échange créé depuis commande " + sourceOrder.getOrderNumber());
        recordStatusChange(saved, OrderStatus.NOUVEAU, OrderStatus.CONFIRME, principal, "Échange confirmé automatiquement");

        orderRepository.save(saved);

        log.info("Exchange order created: {} from source {} (exchange=true)", saved.getOrderNumber(), sourceOrder.getOrderNumber());
        return toDto(saved, true);
    }

    private String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    @Transactional
    public OrderDto updateStatus(Long orderId, UpdateOrderStatusRequest request, UserPrincipal principal) {
        Order order = getOrderById(orderId);
        OrderStatus previousStatus = order.getStatus();
        OrderStatus newStatus = request.getStatus();

        // Validate role permissions for agents
        if (principal != null) {
            User user = userRepository.findById(principal.getId()).orElse(null);
            if (user != null && user.getRole() == Role.AGENT) {
                validateAgentStatusTransition(order, user, newStatus);
            }
        }

        order.setStatus(newStatus);

        if (newStatus.isConfirmed() && order.getConfirmedAt() == null) {
            order.setConfirmedAt(LocalDateTime.now());
            if (request.getDeliveryCityId() != null && !request.getDeliveryCityId().isBlank()) {
                order.setDeliveryCityId(request.getDeliveryCityId());
            }
            // Reserve stock only once (availableStock decreases, currentStock unchanged)
            if (!order.isStockReserved()) {
                reserveStockForOrder(order);
                order.setStockReserved(true);
            }
            // Snapshot du coût de revient au moment de la confirmation
            snapshotUnitCosts(order);
        }

        // LIVRE → finalise la déduction physique (currentStock--, reservedStock--)
        if (newStatus.isDelivered() && !order.isStockDeducted()) {
            if (order.isStockReserved()) {
                finalizeStockDeduction(order);
                order.setStockReserved(false);
            }
            order.setStockDeducted(true);
        }

        if (newStatus.isCancelled() && order.getCancelledAt() == null) {
            order.setCancelledAt(LocalDateTime.now());
            if (order.isStockReserved()) {
                // Libère la réservation (commande annulée avant livraison)
                releaseReservationForOrder(order, "Commande annulée");
                order.setStockReserved(false);
            } else if (order.isStockDeducted()) {
                // Edge case: stock déjà finalisé, on le restaure
                restoreStockForOrder(order, "Commande annulée après livraison");
                order.setStockDeducted(false);
            }
        }

        // Retour physique de la marchandise
        if (newStatus == OrderStatus.RETOURNE) {
            if (order.isStockReserved()) {
                // Retourné avant livraison → libère la réservation
                releaseReservationForOrder(order, "Colis retourné avant livraison");
                order.setStockReserved(false);
            } else if (order.isStockDeducted()) {
                // Retourné après livraison → remettre en stock physique
                restoreStockForOrder(order, "Colis retourné");
                order.setStockDeducted(false);
            }
        }

        recordStatusChange(order, previousStatus, newStatus, principal, request.getNotes());
        return toDto(orderRepository.save(order), true);
    }

    @Transactional
    public OrderDto updateOrder(Long orderId, UpdateOrderRequest request, UserPrincipal principal) {
        Order order = getOrderById(orderId);

        order.setCustomerName(request.getCustomerName());
        String normalizedPhone = PhoneNormalizer.normalize(request.getCustomerPhone());
        order.setCustomerPhone(request.getCustomerPhone());
        order.setCustomerPhoneNormalized(normalizedPhone);
        order.setCustomerPhone2(request.getCustomerPhone2());
        order.setAddress(request.getAddress());
        order.setVille(request.getVille());
        order.setCity(request.getCity() != null && !request.getCity().isBlank()
                ? request.getCity() : request.getVille());
        order.setZipCode(request.getZipCode());
        order.setNotes(request.getNotes());
        if (request.getShippingCost() != null) {
            order.setShippingCost(request.getShippingCost());
        }
        order.setShopifyOrderId(request.getShopifyOrderId());
        order.setExternalRef(request.getExternalRef());

        if (request.getItems() != null && !request.getItems().isEmpty()) {
            // Snapshot des anciens articles avant modification (pour ajuster les réservations)
            List<OrderItem> oldItems = order.isStockReserved()
                    ? new ArrayList<>(order.getItems()) : List.of();

            order.getItems().clear();
            List<OrderItem> newItems = new ArrayList<>();
            for (CreateOrderRequest.OrderItemRequest itemReq : request.getItems()) {
                OrderItem item = new OrderItem();
                item.setProductName(itemReq.getProductName());
                item.setProductSku(itemReq.getProductSku());
                item.setQuantity(itemReq.getQuantity());
                item.setUnitPrice(itemReq.getUnitPrice());
                if (itemReq.getProductId() != null) {
                    productRepository.findById(itemReq.getProductId()).ifPresent(item::setProduct);
                }
                if (itemReq.getVariantId() != null) {
                    productVariantRepository.findById(itemReq.getVariantId()).ifPresent(v -> {
                        item.setVariant(v);
                        if (v.getPriceOverride() != null && itemReq.getUnitPrice() == null) {
                            item.setUnitPrice(v.getPriceOverride());
                        }
                    });
                }
                item.calculateTotalPrice();
                order.addItem(item);
                newItems.add(item);
            }
            order.recalculateTotals();

            // Ajuster les réservations de stock si la commande est confirmée
            if (order.isStockReserved()) {
                adjustStockReservations(order, oldItems, newItems);
            }
        }

        return toDto(orderRepository.save(order), true);
    }

    @Transactional
    public OrderDto assignOrder(Long orderId, AssignOrderRequest request, UserPrincipal principal) {
        Order order = getOrderById(orderId);
        User agent = userRepository.findById(request.getAgentId())
                .orElseThrow(() -> new ResourceNotFoundException("Agent", request.getAgentId()));

        if (agent.getRole() != Role.AGENT) {
            throw new BusinessException("L'utilisateur sélectionné n'est pas un agent");
        }

        order.setAssignedTo(agent);
        order.setAssignedAt(LocalDateTime.now());

        return toDto(orderRepository.save(order), false);
    }

    @Transactional
    public void bulkAssignOrders(AssignOrderRequest request, UserPrincipal principal) {
        if (request.getOrderIds() == null || request.getOrderIds().isEmpty()) {
            throw new BusinessException("Aucune commande sélectionnée");
        }
        User agent = userRepository.findById(request.getAgentId())
                .orElseThrow(() -> new ResourceNotFoundException("Agent", request.getAgentId()));

        if (agent.getRole() != Role.AGENT) {
            throw new BusinessException("L'utilisateur sélectionné n'est pas un agent");
        }

        request.getOrderIds().forEach(orderId -> {
            orderRepository.findById(orderId).ifPresent(order -> {
                order.setAssignedTo(agent);
                order.setAssignedAt(LocalDateTime.now());
                orderRepository.save(order);
            });
        });
        log.info("Bulk assigned {} orders to agent {}", request.getOrderIds().size(), agent.getUsername());
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderDto> getOrders(OrderStatus status, Collection<String> rawStatuses,
                                            OrderSource source,
                                            Long assignedTo, String search,
                                            LocalDateTime from, LocalDateTime to,
                                            Pageable pageable, UserPrincipal principal) {
        // Agents can only see their own assigned orders — use role from JWT token directly
        Long filterAssignedTo = assignedTo;
        if (principal != null && Role.AGENT.name().equals(principal.getRole())) {
            filterAssignedTo = principal.getId();
        }
        Collection<OrderStatus> statuses = expandStatusAliases(rawStatuses);
        Page<Order> page = orderRepository.findAll(
                OrderSpecification.withFilters(status, statuses, source, filterAssignedTo, search, from, to), pageable);
        return PageResponse.of(page.map(o -> toDto(o, false)));
    }

    /**
     * Expands frontend-friendly status aliases to actual OrderStatus values.
     * CANCELLED maps to the full cancelled group (ANNULE, PAS_SERIEUX, FAKE_ORDER).
     * RETURNED maps to RETOURNE.
     */
    private Collection<OrderStatus> expandStatusAliases(Collection<String> rawStatuses) {
        if (rawStatuses == null || rawStatuses.isEmpty()) return null;
        List<OrderStatus> result = new ArrayList<>();
        for (String s : rawStatuses) {
            switch (s.toUpperCase()) {
                case "CANCELLED" -> { result.add(OrderStatus.ANNULE); result.add(OrderStatus.PAS_SERIEUX); result.add(OrderStatus.FAKE_ORDER); }
                case "RETURNED"  -> result.add(OrderStatus.RETOURNE);
                default -> {
                    try { result.add(OrderStatus.valueOf(s.toUpperCase())); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(Long id, UserPrincipal principal) {
        Order order = getOrderById(id);
        // Agents can only access orders assigned to them
        if (principal != null && Role.AGENT.name().equals(principal.getRole())) {
            if (order.getAssignedTo() == null || !order.getAssignedTo().getId().equals(principal.getId())) {
                throw new BusinessException("Accès non autorisé à cette commande");
            }
        }
        return toDto(order, true);
    }

    @Transactional(readOnly = true)
    public List<OrderDto> getMyOrders(UserPrincipal principal) {
        return orderRepository.findActiveOrdersByAgent(principal.getId())
                .stream().map(o -> toDto(o, false)).toList();
    }

    private String generateOrderNumber(CreateOrderRequest request) {
        if (StringUtils.hasText(request.getOrderNumber())) {
            if (orderRepository.existsByOrderNumber(request.getOrderNumber())) {
                throw new BusinessException("Ce numéro de commande existe déjà: " + request.getOrderNumber());
            }
            return request.getOrderNumber();
        }
        // Auto-generate: COD-YYYYMMDD-XXXX
        String prefix = "COD-" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String unique = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return prefix + "-" + unique;
    }

    private void validateAgentStatusTransition(Order order, User agent, OrderStatus newStatus) {
        if (order.getAssignedTo() == null || !order.getAssignedTo().getId().equals(agent.getId())) {
            throw new BusinessException("Cette commande ne vous est pas assignée");
        }
    }

    private void recordStatusChange(Order order, OrderStatus from, OrderStatus to, UserPrincipal principal, String notes) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setNotes(notes);
        if (principal != null) {
            userRepository.findById(principal.getId()).ifPresent(history::setChangedBy);
        }
        statusHistoryRepository.save(history);
    }

    private void reserveStockForOrder(Order order) {
        order.getItems().forEach(item -> {
            if (item.getProduct() != null) {
                try {
                    Long variantId = item.getVariant() != null ? item.getVariant().getId() : null;
                    stockService.reserveStockForOrder(item.getProduct().getId(), variantId, item.getQuantity(), order.getId());
                    log.info("[STOCK-RESERVE] Commande {} — produit id={} sku='{}' variantId={} qty={}",
                            order.getOrderNumber(), item.getProduct().getId(), item.getProductSku(), variantId, item.getQuantity());
                } catch (Exception e) {
                    log.warn("Could not reserve stock for product {} in order {}: {}",
                            item.getProduct().getSku(), order.getOrderNumber(), e.getMessage());
                }
            } else {
                log.warn("[STOCK-RESERVE] Commande {} — article '{}' sku='{}' sans produit lié, stock non réservé.",
                        order.getOrderNumber(), item.getProductName(), item.getProductSku());
            }
        });
    }

    private void finalizeStockDeduction(Order order) {
        order.getItems().forEach(item -> {
            if (item.getProduct() != null) {
                try {
                    Long variantId = item.getVariant() != null ? item.getVariant().getId() : null;
                    stockService.finalizeStockDeduction(item.getProduct().getId(), variantId, item.getQuantity(), order.getId());
                    log.info("[STOCK-FINALIZE] Commande {} — produit id={} sku='{}' variantId={} qty={}",
                            order.getOrderNumber(), item.getProduct().getId(), item.getProductSku(), variantId, item.getQuantity());
                } catch (Exception e) {
                    log.warn("Could not finalize stock for product {} in order {}: {}",
                            item.getProduct().getSku(), order.getOrderNumber(), e.getMessage());
                }
            }
        });
    }

    private void releaseReservationForOrder(Order order, String reason) {
        order.getItems().forEach(item -> {
            if (item.getProduct() != null) {
                try {
                    Long variantId = item.getVariant() != null ? item.getVariant().getId() : null;
                    stockService.releaseReservation(item.getProduct().getId(), variantId, item.getQuantity(), order.getId(), reason);
                    log.info("[STOCK-RELEASE] Commande {} — produit id={} sku='{}' variantId={} qty={} raison={}",
                            order.getOrderNumber(), item.getProduct().getId(), item.getProductSku(), variantId, item.getQuantity(), reason);
                } catch (Exception e) {
                    log.warn("Could not release reservation for product {} in order {}: {}",
                            item.getProduct().getSku(), order.getOrderNumber(), e.getMessage());
                }
            }
        });
    }

    private void snapshotUnitCosts(Order order) {
        order.getItems().forEach(item -> {
            if (item.getUnitCost() != null) return; // already snapshotted
            try {
                java.math.BigDecimal cost = null;
                if (item.getVariant() != null && item.getVariant().getCostPrice() != null) {
                    cost = item.getVariant().getCostPrice();
                } else if (item.getProduct() != null && item.getProduct().getCostPrice() != null) {
                    cost = item.getProduct().getCostPrice();
                }
                if (cost != null) item.setUnitCost(cost);
            } catch (Exception e) {
                log.warn("Could not snapshot unit cost for item {} in order {}: {}",
                        item.getProductSku(), order.getOrderNumber(), e.getMessage());
            }
        });
    }

    private void restoreStockForOrder(Order order, String reason) {
        order.getItems().forEach(item -> {
            if (item.getProduct() != null) {
                try {
                    Long variantId = item.getVariant() != null ? item.getVariant().getId() : null;
                    stockService.restoreStockForOrder(item.getProduct().getId(), variantId, item.getQuantity(), order.getId(), reason);
                    log.info("[STOCK-RESTORE] Commande {} — produit id={} sku='{}' variantId={} qty={} raison={}",
                            order.getOrderNumber(), item.getProduct().getId(), item.getProductSku(), variantId, item.getQuantity(), reason);
                } catch (Exception e) {
                    log.warn("Could not restore stock for product {} in order {}: {}",
                            item.getProduct().getSku(), order.getOrderNumber(), e.getMessage());
                }
            }
        });
    }

    /**
     * Ajuste les réservations de stock lors de la modification d'une commande confirmée.
     * Clé de comparaison : (productId, variantId).
     * - Article supprimé ou quantité réduite → libère la réservation du delta
     * - Article ajouté ou quantité augmentée → réserve le delta
     * - Variante changée → libère l'ancienne, réserve la nouvelle
     */
    private void adjustStockReservations(Order order, List<OrderItem> oldItems, List<OrderItem> newItems) {
        record StockKey(Long productId, Long variantId) {}

        Map<StockKey, Integer> oldQty = new HashMap<>();
        for (OrderItem item : oldItems) {
            if (item.getProduct() != null) {
                StockKey key = new StockKey(item.getProduct().getId(),
                        item.getVariant() != null ? item.getVariant().getId() : null);
                oldQty.merge(key, item.getQuantity(), Integer::sum);
            }
        }

        Map<StockKey, Integer> newQty = new HashMap<>();
        for (OrderItem item : newItems) {
            if (item.getProduct() != null) {
                StockKey key = new StockKey(item.getProduct().getId(),
                        item.getVariant() != null ? item.getVariant().getId() : null);
                newQty.merge(key, item.getQuantity(), Integer::sum);
            }
        }

        // Libérer le delta pour les articles supprimés ou réduits
        for (Map.Entry<StockKey, Integer> entry : oldQty.entrySet()) {
            StockKey key = entry.getKey();
            int delta = entry.getValue() - newQty.getOrDefault(key, 0);
            if (delta > 0) {
                try {
                    stockService.releaseReservation(key.productId(), key.variantId(), delta, order.getId(),
                            "Modification commande confirmée");
                    log.info("[STOCK-ADJUST] Commande {} — libération {} unité(s) produit={} variant={}",
                            order.getOrderNumber(), delta, key.productId(), key.variantId());
                } catch (Exception e) {
                    log.warn("Could not release reservation on order update {}: {}", order.getOrderNumber(), e.getMessage());
                }
            }
        }

        // Réserver le delta pour les articles ajoutés ou augmentés
        for (Map.Entry<StockKey, Integer> entry : newQty.entrySet()) {
            StockKey key = entry.getKey();
            int delta = entry.getValue() - oldQty.getOrDefault(key, 0);
            if (delta > 0) {
                try {
                    stockService.reserveStockForOrder(key.productId(), key.variantId(), delta, order.getId());
                    log.info("[STOCK-ADJUST] Commande {} — réservation {} unité(s) produit={} variant={}",
                            order.getOrderNumber(), delta, key.productId(), key.variantId());
                } catch (Exception e) {
                    log.warn("Could not reserve stock on order update {}: {}", order.getOrderNumber(), e.getMessage());
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Long> countByStatus() {
        Map<String, Long> result = new LinkedHashMap<>();
        orderRepository.countGroupByStatus().forEach(row -> {
            OrderStatus status = (OrderStatus) row[0];
            Long count = (Long) row[1];
            result.put(status.name(), count);
        });
        return result;
    }

    private Order getOrderById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Commande", id));
    }

    private Customer findOrCreateCustomer(String phone, String phoneNormalized,
                                          String fullName, String address, String ville) {
        try {
            if (phoneNormalized != null) {
                return customerRepository.findByPhoneNormalized(phoneNormalized)
                        .orElseGet(() -> createCustomer(phone, phoneNormalized, fullName, address, ville));
            }
            return customerRepository.findByPhone(phone)
                    .orElseGet(() -> createCustomer(phone, null, fullName, address, ville));
        } catch (Exception e) {
            log.warn("Could not link customer for phone {}: {}", phone, e.getMessage());
            return null;
        }
    }

    private Customer createCustomer(String phone, String phoneNormalized,
                                    String fullName, String address, String ville) {
        com.codflow.backend.customer.entity.Customer c = new com.codflow.backend.customer.entity.Customer();
        c.setPhone(phone);
        c.setPhoneNormalized(phoneNormalized);
        c.setFullName(fullName != null ? fullName : "Client inconnu");
        c.setAddress(address);
        c.setVille(ville);
        c.setStatus(com.codflow.backend.customer.enums.CustomerStatus.ACTIVE);
        return customerRepository.save(c);
    }

    public OrderDto toDto(Order order, boolean withHistory) {
        List<OrderItemDto> items = order.getItems().stream().map(item -> {
            ProductVariant v = item.getVariant();
            return OrderItemDto.builder()
                    .id(item.getId())
                    .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                    .variantId(v != null ? v.getId() : null)
                    .variantColor(v != null ? v.getColor() : null)
                    .variantSize(v != null ? v.getSize() : null)
                    .productName(item.getProductName())
                    .productSku(item.getProductSku())
                    .quantity(item.getQuantity())
                    .unitPrice(item.getUnitPrice())
                    .totalPrice(item.getTotalPrice())
                    .build();
        }).toList();

        // Delivery status from shipment
        DeliveryShipment shipment = shipmentRepository.findByOrderId(order.getId()).orElse(null);

        List<OrderStatusHistoryDto> history = withHistory
                ? order.getStatusHistory().stream().map(h ->
                    OrderStatusHistoryDto.builder()
                            .id(h.getId())
                            .fromStatus(h.getFromStatus())
                            .fromStatusLabel(h.getFromStatus() != null ? h.getFromStatus().getLabel() : null)
                            .toStatus(h.getToStatus())
                            .toStatusLabel(h.getToStatus().getLabel())
                            .changedByName(h.getChangedBy() != null ? h.getChangedBy().getFullName() : "Système")
                            .notes(h.getNotes())
                            .createdAt(h.getCreatedAt())
                            .build()
                ).toList()
                : null;

        return OrderDto.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .source(order.getSource())
                .customerName(order.getCustomerName())
                .customerPhone(order.getCustomerPhone())
                .customerPhone2(order.getCustomerPhone2())
                .address(order.getAddress())
                .city(order.getCity())
                .ville(order.getVille())
                .deliveryCityId(order.getDeliveryCityId())
                .zipCode(order.getZipCode())
                .notes(order.getNotes())
                .subtotal(order.getSubtotal())
                .shippingCost(order.getShippingCost())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .statusLabel(order.getStatus().getLabel())
                .potentialDuplicate(order.isPotentialDuplicate())
                .deliveryStatus(shipment != null ? shipment.getStatus() : null)
                .deliveryStatusLabel(shipment != null ? shipment.getStatus().getLabel() : null)
                .trackingNumber(shipment != null ? shipment.getTrackingNumber() : null)
                .customerId(order.getCustomer() != null ? order.getCustomer().getId() : null)
                .assignedToId(order.getAssignedTo() != null ? order.getAssignedTo().getId() : null)
                .assignedToName(order.getAssignedTo() != null ? order.getAssignedTo().getFullName() : null)
                .assignedAt(order.getAssignedAt())
                .confirmedAt(order.getConfirmedAt())
                .cancelledAt(order.getCancelledAt())
                .shopifyOrderId(order.getShopifyOrderId())
                .isExchange(order.isExchange())
                .sourceOrderId(order.getSourceOrder() != null ? order.getSourceOrder().getId() : null)
                .sourceOrderNumber(order.getSourceOrder() != null ? order.getSourceOrder().getOrderNumber() : null)

                .items(items)
                .statusHistory(history)
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
