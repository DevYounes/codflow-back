package com.codflow.backend.order.service;

import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.common.util.PhoneNormalizer;
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
import java.util.List;
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
            // Deduct stock for confirmed orders
            deductStockForOrder(order);
        }

        if (newStatus.isCancelled() && order.getCancelledAt() == null) {
            order.setCancelledAt(LocalDateTime.now());
            // Restore stock if was confirmed
            if (previousStatus.isConfirmed()) {
                restoreStockForOrder(order);
            }
        }

        recordStatusChange(order, previousStatus, newStatus, principal, request.getNotes());
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
    public PageResponse<OrderDto> getOrders(OrderStatus status, java.util.Collection<OrderStatus> statuses,
                                            OrderSource source,
                                            Long assignedTo, String search,
                                            LocalDateTime from, LocalDateTime to,
                                            Pageable pageable, UserPrincipal principal) {
        // Agents can only see their own assigned orders — use role from JWT token directly
        Long filterAssignedTo = assignedTo;
        if (principal != null && Role.AGENT.name().equals(principal.getRole())) {
            filterAssignedTo = principal.getId();
        }
        Page<Order> page = orderRepository.findAll(
                OrderSpecification.withFilters(status, statuses, source, filterAssignedTo, search, from, to), pageable);
        return PageResponse.of(page.map(o -> toDto(o, false)));
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

    private void deductStockForOrder(Order order) {
        order.getItems().forEach(item -> {
            if (item.getProduct() != null) {
                try {
                    stockService.deductStockForOrder(item.getProduct().getId(), item.getQuantity(), order.getId());
                } catch (Exception e) {
                    log.warn("Could not deduct stock for product {} in order {}: {}",
                            item.getProduct().getSku(), order.getOrderNumber(), e.getMessage());
                }
            }
        });
    }

    private void restoreStockForOrder(Order order) {
        order.getItems().forEach(item -> {
            if (item.getProduct() != null) {
                try {
                    stockService.restoreStockForCancelledOrder(item.getProduct().getId(), item.getQuantity(), order.getId());
                } catch (Exception e) {
                    log.warn("Could not restore stock for product {} in order {}: {}",
                            item.getProduct().getSku(), order.getOrderNumber(), e.getMessage());
                }
            }
        });
    }

    private Order getOrderById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Commande", id));
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
                .assignedToId(order.getAssignedTo() != null ? order.getAssignedTo().getId() : null)
                .assignedToName(order.getAssignedTo() != null ? order.getAssignedTo().getFullName() : null)
                .assignedAt(order.getAssignedAt())
                .confirmedAt(order.getConfirmedAt())
                .cancelledAt(order.getCancelledAt())
                .shopifyOrderId(order.getShopifyOrderId())
                .items(items)
                .statusHistory(history)
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
