package com.codflow.backend.delivery.service;

import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.delivery.dto.ConfirmReturnRequest;
import com.codflow.backend.delivery.dto.CreateShipmentRequest;
import com.codflow.backend.delivery.dto.DeliveryShipmentDto;
import com.codflow.backend.delivery.dto.PendingReturnDto;
import com.codflow.backend.delivery.dto.RequestPickupDto;
import com.codflow.backend.delivery.entity.DeliveryProviderConfig;
import com.codflow.backend.delivery.entity.DeliveryShipment;
import com.codflow.backend.delivery.entity.DeliveryTrackingHistory;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import com.codflow.backend.delivery.provider.DeliveryProviderAdapter;
import com.codflow.backend.delivery.provider.DeliveryProviderRegistry;
import com.codflow.backend.delivery.provider.dto.*;
import com.codflow.backend.delivery.provider.ozon.OzonCityDto;
import com.codflow.backend.delivery.provider.ozon.OzonCityService;
import com.codflow.backend.delivery.repository.DeliveryProviderRepository;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import com.codflow.backend.order.entity.Order;
import com.codflow.backend.order.enums.OrderStatus;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.order.service.OrderService;
import com.codflow.backend.order.dto.UpdateOrderStatusRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryShipmentRepository shipmentRepository;
    private final DeliveryProviderRepository providerRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final DeliveryProviderRegistry providerRegistry;
    private final OzonCityService ozonCityService;

    @Transactional
    public DeliveryShipmentDto createShipment(CreateShipmentRequest request) {
        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Commande", request.getOrderId()));

        if (order.getStatus() != OrderStatus.CONFIRME && order.getStatus() != OrderStatus.EN_PREPARATION) {
            throw new BusinessException("La commande doit être confirmée avant d'envoyer en livraison");
        }

        if (shipmentRepository.findByOrderId(order.getId()).isPresent()) {
            throw new BusinessException("Un envoi existe déjà pour cette commande");
        }

        DeliveryProviderConfig providerConfig = providerRepository.findById(request.getProviderId())
                .orElseThrow(() -> new ResourceNotFoundException("Transporteur", request.getProviderId()));

        if (!providerRegistry.hasProvider(providerConfig.getCode())) {
            throw new BusinessException("Transporteur non supporté: " + providerConfig.getCode());
        }

        DeliveryProviderAdapter adapter = providerRegistry.getProvider(providerConfig.getCode());
        DeliveryProviderAdapter.ProviderConfig adapterConfig = new DeliveryProviderAdapter.ProviderConfig(
                providerConfig.getApiBaseUrl(),
                providerConfig.getApiKey(),
                providerConfig.getApiToken(),
                providerConfig.getConfig()
        );

        ShipmentRequest shipmentRequest = buildShipmentRequest(order);
        ShipmentResponse response = adapter.createShipment(shipmentRequest, adapterConfig);

        DeliveryShipment shipment = new DeliveryShipment();
        shipment.setOrder(order);
        shipment.setProvider(providerConfig);
        shipment.setNotes(request.getNotes());

        // Snapshot city tariffs at creation time so charges are stable even if prices change later
        if (order.getDeliveryCityId() != null) {
            OzonCityDto city = ozonCityService.findById(order.getDeliveryCityId());
            if (city != null) {
                shipment.setDeliveredPrice(city.deliveredPrice());
                shipment.setReturnedPrice(city.returnedPrice());
                shipment.setRefusedPrice(city.refusedPrice());
            }
        }

        if (response.isSuccess()) {
            shipment.setTrackingNumber(response.getTrackingNumber());
            shipment.setProviderOrderId(response.getProviderOrderId());
            shipment.setStatus(ShipmentStatus.CREATED);
            shipment.setShippedAt(LocalDateTime.now());
            shipment.setRawResponse(response.getRawResponse());

            // Update order status
            UpdateOrderStatusRequest statusUpdate = new UpdateOrderStatusRequest();
            statusUpdate.setStatus(OrderStatus.ENVOYE);
            statusUpdate.setNotes("Envoyé via " + providerConfig.getName() + " - Tracking: " + response.getTrackingNumber());
            orderService.updateStatus(order.getId(), statusUpdate, null);
        } else {
            shipment.setStatus(ShipmentStatus.PENDING);
            log.error("Failed to create shipment with {}: {}", providerConfig.getName(), response.getMessage());
        }

        return toDto(shipmentRepository.save(shipment), true);
    }

    @Transactional
    public DeliveryShipmentDto requestPickup(RequestPickupDto request) {
        DeliveryProviderConfig providerConfig = providerRepository.findById(request.getProviderId())
                .orElseThrow(() -> new ResourceNotFoundException("Transporteur", request.getProviderId()));

        List<DeliveryShipment> shipments;
        if (request.getShipmentIds() != null && !request.getShipmentIds().isEmpty()) {
            shipments = shipmentRepository.findAllById(request.getShipmentIds());
        } else {
            shipments = shipmentRepository.findByStatus(ShipmentStatus.CREATED);
        }

        List<String> trackingNumbers = shipments.stream()
                .filter(s -> s.getTrackingNumber() != null)
                .map(DeliveryShipment::getTrackingNumber)
                .collect(Collectors.toList());

        if (trackingNumbers.isEmpty()) {
            throw new BusinessException("Aucun colis disponible pour le ramassage");
        }

        DeliveryProviderAdapter adapter = providerRegistry.getProvider(providerConfig.getCode());
        DeliveryProviderAdapter.ProviderConfig adapterConfig = new DeliveryProviderAdapter.ProviderConfig(
                providerConfig.getApiBaseUrl(), providerConfig.getApiKey(),
                providerConfig.getApiToken(), providerConfig.getConfig()
        );

        PickupRequest pickupRequest = PickupRequest.builder()
                .trackingNumbers(trackingNumbers)
                .pickupDate(request.getPickupDate())
                .notes(request.getNotes())
                .build();

        PickupResponse response = adapter.requestPickup(pickupRequest, adapterConfig);

        if (response.isSuccess()) {
            shipments.forEach(s -> {
                s.setPickupRequested(true);
                s.setPickupRequestedAt(LocalDateTime.now());
                s.setStatus(ShipmentStatus.PICKUP_REQUESTED);
                shipmentRepository.save(s);
            });
            log.info("Pickup requested for {} shipments, pickup ID: {}", trackingNumbers.size(), response.getPickupId());
        } else {
            log.error("Failed to request pickup: {}", response.getMessage());
            throw new BusinessException("Erreur lors de la demande de ramassage: " + response.getMessage());
        }

        return shipments.isEmpty() ? null : toDto(shipments.get(0), false);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<DeliveryShipmentDto> listShipments(
            List<ShipmentStatus> statuses, String search, LocalDate from, LocalDate to, Pageable pageable) {
        Specification<DeliveryShipment> spec = Specification.where(null);

        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("status").in(statuses));
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("trackingNumber")), like),
                    cb.like(cb.lower(root.join("order").get("customerName")), like),
                    cb.like(cb.lower(root.join("order").get("orderNumber")), like)
            ));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay()));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay()));
        }

        return shipmentRepository.findAll(spec, pageable).map(s -> toDto(s, false));
    }

    @Transactional(readOnly = true)
    public DeliveryShipmentDto getShipmentById(Long id) {
        DeliveryShipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Envoi", id));
        return toDto(shipment, true);
    }

    @Transactional(readOnly = true)
    public DeliveryShipmentDto getShipmentByOrderId(Long orderId) {
        DeliveryShipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Envoi pour la commande", orderId));
        return toDto(shipment, true);
    }

    @Transactional
    public DeliveryShipmentDto syncTracking(Long shipmentId) {
        DeliveryShipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Envoi", shipmentId));
        syncShipmentTracking(shipment);
        return toDto(shipmentRepository.save(shipment), true);
    }

    @Scheduled(fixedRateString = "${app.delivery.sync-interval-ms}")
    @Transactional
    public void syncAllActiveShipments() {
        List<DeliveryShipment> activeShipments = shipmentRepository.findActiveShipments();
        if (activeShipments.isEmpty()) return;

        log.info("Syncing tracking for {} active shipments...", activeShipments.size());
        activeShipments.forEach(shipment -> {
            try {
                syncShipmentTracking(shipment);
                shipmentRepository.save(shipment);
            } catch (Exception e) {
                log.error("Error syncing tracking for shipment {}: {}", shipment.getTrackingNumber(), e.getMessage());
            }
        });
    }

    private void syncShipmentTracking(DeliveryShipment shipment) {
        if (shipment.getTrackingNumber() == null) return;

        DeliveryProviderConfig providerConfig = shipment.getProvider();
        if (!providerRegistry.hasProvider(providerConfig.getCode())) return;

        DeliveryProviderAdapter adapter = providerRegistry.getProvider(providerConfig.getCode());
        DeliveryProviderAdapter.ProviderConfig adapterConfig = new DeliveryProviderAdapter.ProviderConfig(
                providerConfig.getApiBaseUrl(), providerConfig.getApiKey(),
                providerConfig.getApiToken(), providerConfig.getConfig()
        );

        TrackingInfo info = adapter.trackShipment(shipment.getTrackingNumber(), adapterConfig);
        if (info == null || "UNKNOWN".equals(info.getStatus())) return;

        // Store provider's human-readable status label
        if (info.getStatusDescription() != null) {
            shipment.setProviderStatusLabel(info.getStatusDescription());
        }

        // Map provider status to our enum
        ShipmentStatus newStatus = mapProviderStatus(info.getStatus());
        if (newStatus != null && newStatus != shipment.getStatus()) {
            updateShipmentStatus(shipment, newStatus);
        }

        // Record new tracking events (skip duplicates)
        if (info.getEvents() != null) {
            info.getEvents().forEach(event -> {
                LocalDateTime eventAt = event.getEventAt() != null ? event.getEventAt() : LocalDateTime.now();
                boolean alreadyExists = shipment.getTrackingHistory().stream().anyMatch(h ->
                        h.getStatus().equals(event.getStatus()) && h.getEventAt().equals(eventAt));
                if (!alreadyExists) {
                    DeliveryTrackingHistory history = new DeliveryTrackingHistory();
                    history.setShipment(shipment);
                    history.setStatus(event.getStatus());
                    history.setDescription(event.getDescription());
                    history.setLocation(event.getLocation());
                    history.setEventAt(eventAt);
                    shipment.getTrackingHistory().add(history);
                }
            });
        }
    }

    private void updateShipmentStatus(DeliveryShipment shipment, ShipmentStatus newStatus) {
        shipment.setStatus(newStatus);
        LocalDateTime now = LocalDateTime.now();

        // Update order status based on delivery status
        UpdateOrderStatusRequest statusUpdate = new UpdateOrderStatusRequest();

        switch (newStatus) {
            case OUT_FOR_DELIVERY -> {
                shipment.setOutForDeliveryAt(now);
                statusUpdate.setStatus(OrderStatus.EN_LIVRAISON);
            }
            case DELIVERED -> {
                shipment.setDeliveredAt(now);
                statusUpdate.setStatus(OrderStatus.LIVRE);
                shipment.setAppliedFee(shipment.getDeliveredPrice());
                shipment.setAppliedFeeType("LIVRAISON");
            }
            case RETURNED -> {
                shipment.setReturnedAt(now);
                statusUpdate.setStatus(OrderStatus.RETOURNE);
                // RETURNED-PRICE (souvent 0 MAD chez Ozon)
                shipment.setAppliedFee(shipment.getReturnedPrice());
                shipment.setAppliedFeeType("RETOUR");
            }
            case FAILED_DELIVERY -> {
                statusUpdate.setStatus(OrderStatus.ECHEC_LIVRAISON);
                // Client a refusé à la porte → REFUSED-PRICE
                shipment.setAppliedFee(shipment.getRefusedPrice());
                shipment.setAppliedFeeType("REFUS");
            }
            case CANCELLED -> {
                shipment.setAppliedFee(java.math.BigDecimal.ZERO);
                shipment.setAppliedFeeType("ANNULATION");
            }
            default -> { return; }
        }

        statusUpdate.setNotes("Mise à jour automatique depuis " + shipment.getProvider().getName());
        try {
            orderService.updateStatus(shipment.getOrder().getId(), statusUpdate, null);
        } catch (Exception e) {
            log.error("Could not update order status for delivery update: {}", e.getMessage());
        }
    }

    /**
     * Backfille les prix Ozon (delivered/returned/refused) et applied_fee
     * pour les colis dont les tarifs sont NULL (créés avant la feature de snapshot).
     * Utilise les tarifs actuels de l'API Ozon — approx. pour l'historique.
     */
    @Transactional
    public int repairShipmentFees() {
        List<DeliveryShipment> toRepair = shipmentRepository.findAll().stream()
                .filter(s -> s.getDeliveredPrice() == null || s.getAppliedFeeType() == null)
                .toList();

        int repaired = 0;
        for (DeliveryShipment s : toRepair) {
            String cityId = s.getOrder().getDeliveryCityId();
            if (cityId != null) {
                OzonCityDto city = ozonCityService.findById(cityId);
                if (city != null) {
                    s.setDeliveredPrice(city.deliveredPrice());
                    s.setReturnedPrice(city.returnedPrice());
                    s.setRefusedPrice(city.refusedPrice());
                }
            }
            // Recalcule applied_fee selon le statut
            if (s.getAppliedFeeType() == null) {
                switch (s.getStatus()) {
                    case DELIVERED      -> { s.setAppliedFee(s.getDeliveredPrice()); s.setAppliedFeeType("LIVRAISON"); }
                    case RETURNED       -> { s.setAppliedFee(s.getReturnedPrice());  s.setAppliedFeeType("RETOUR"); }
                    case FAILED_DELIVERY -> { s.setAppliedFee(s.getRefusedPrice());  s.setAppliedFeeType("REFUS"); }
                    case CANCELLED      -> { s.setAppliedFee(java.math.BigDecimal.ZERO); s.setAppliedFeeType("ANNULATION"); }
                    default -> {}
                }
            } else if (s.getAppliedFee() == null || s.getAppliedFee().compareTo(java.math.BigDecimal.ZERO) == 0) {
                // appliedFeeType déjà set par V12 mais appliedFee était 0 car prix null
                switch (s.getAppliedFeeType()) {
                    case "LIVRAISON" -> s.setAppliedFee(s.getDeliveredPrice());
                    case "RETOUR"    -> s.setAppliedFee(s.getReturnedPrice());
                    case "REFUS"     -> s.setAppliedFee(s.getRefusedPrice());
                    default -> {}
                }
            }
            shipmentRepository.save(s);
            repaired++;
        }
        log.info("repairShipmentFees: {} colis réparés", repaired);
        return repaired;
    }

    // =========================================================================
    // Suivi des retours physiques
    // =========================================================================

    /** Seuil en jours au-delà duquel un retour non confirmé est considéré "en retard". */
    private static final int OVERDUE_DAYS = 7;

    /**
     * Retourne tous les colis dont le retour physique n'a pas encore été confirmé.
     * Statuts concernés : FAILED_DELIVERY (refusé), RETURNED (le transporteur dit retourné),
     * CANCELLED (annulé après expédition).
     */
    @Transactional(readOnly = true)
    public List<PendingReturnDto> getPendingReturns() {
        return shipmentRepository.findPendingReturns().stream()
                .map(this::toPendingReturnDto)
                .collect(Collectors.toList());
    }

    /**
     * Confirme la réception physique du colis retourné par le marchand.
     * Met returnReceived = true et stocke la date + notes optionnelles.
     */
    @Transactional
    public DeliveryShipmentDto confirmReturnReceived(Long shipmentId, ConfirmReturnRequest request) {
        DeliveryShipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Colis", shipmentId));

        if (shipment.isReturnReceived()) {
            throw new BusinessException("Le retour de ce colis a déjà été confirmé");
        }

        List<ShipmentStatus> returnableStatuses = List.of(
                ShipmentStatus.FAILED_DELIVERY, ShipmentStatus.RETURNED, ShipmentStatus.CANCELLED);
        if (!returnableStatuses.contains(shipment.getStatus())) {
            throw new BusinessException(
                    "Ce colis n'est pas en état de retour (statut actuel: " + shipment.getStatus().getLabel() + ")");
        }

        shipment.setReturnReceived(true);
        shipment.setReturnReceivedAt(LocalDateTime.now());
        if (request != null && request.getNotes() != null) {
            shipment.setReturnReceivedNotes(request.getNotes());
        }

        log.info("[RETOUR CONFIRMÉ] Colis {} (commande {}) — reçu physiquement",
                shipment.getTrackingNumber(), shipment.getOrder().getOrderNumber());
        return toDto(shipmentRepository.save(shipment), false);
    }

    /**
     * Vérification quotidienne à 8h00 — alerte si des retours sont en attente depuis trop longtemps.
     * Permet de détecter les colis refusés/annulés que le transporteur ne retourne pas.
     */
    @Scheduled(cron = "0 0 8 * * *")
    public void checkOverdueReturns() {
        List<PendingReturnDto> overdue = getPendingReturns().stream()
                .filter(PendingReturnDto::isOverdue)
                .collect(Collectors.toList());

        if (!overdue.isEmpty()) {
            log.warn("[RETOUR EN RETARD] {} colis sans confirmation de retour depuis plus de {} jours :",
                    overdue.size(), OVERDUE_DAYS);
            overdue.forEach(r -> log.warn("  ⚠ {} | Commande {} | {} | {} jours | Statut: {}",
                    r.getTrackingNumber(), r.getOrderNumber(), r.getCustomerName(),
                    r.getDaysPending(), r.getStatusLabel()));
        } else {
            log.debug("[RETOUR] Aucun retour en retard détecté");
        }
    }

    private PendingReturnDto toPendingReturnDto(DeliveryShipment s) {
        LocalDateTime changedAt = s.getReturnedAt() != null ? s.getReturnedAt() : s.getUpdatedAt();
        long days = ChronoUnit.DAYS.between(changedAt, LocalDateTime.now());
        return PendingReturnDto.builder()
                .shipmentId(s.getId())
                .trackingNumber(s.getTrackingNumber())
                .orderId(s.getOrder().getId())
                .orderNumber(s.getOrder().getOrderNumber())
                .customerName(s.getOrder().getCustomerName())
                .customerPhone(s.getOrder().getCustomerPhone())
                .city(s.getOrder().getCity())
                .status(s.getStatus())
                .statusLabel(s.getProviderStatusLabel() != null
                        ? s.getProviderStatusLabel() : s.getStatus().getLabel())
                .providerStatusLabel(s.getProviderStatusLabel())
                .statusChangedAt(changedAt)
                .daysPending(days)
                .overdue(days >= OVERDUE_DAYS)
                .appliedFee(s.getAppliedFee())
                .appliedFeeType(s.getAppliedFeeType())
                .build();
    }

    private ShipmentStatus mapProviderStatus(String providerStatus) {
        if (providerStatus == null) return null;
        return switch (providerStatus.toLowerCase()) {
            // Ozon Express French labels
            case "nouveau colis" -> ShipmentStatus.CREATED;
            case "en attente de ramassage", "attente de ramassage" -> ShipmentStatus.PICKUP_REQUESTED;
            case "ramassé", "ramasse", "enlevé", "enleve", "collecté", "collecte" -> ShipmentStatus.PICKED_UP;
            case "en transit", "en cours de traitement", "en traitement" -> ShipmentStatus.IN_TRANSIT;
            case "en cours de livraison", "sorti en livraison" -> ShipmentStatus.OUT_FOR_DELIVERY;
            case "livré", "livre" -> ShipmentStatus.DELIVERED;
            case "tentative échouée", "tentative echouee", "echec de livraison", "échec de livraison" -> ShipmentStatus.FAILED_DELIVERY;
            case "retour", "en retour", "retourné", "retourne", "retour en cours" -> ShipmentStatus.RETURNED;
            case "annulé", "annule" -> ShipmentStatus.CANCELLED;
            default -> null;
        };
    }

    private ShipmentRequest buildShipmentRequest(Order order) {
        List<ShipmentRequest.ShipmentItemRequest> items = order.getItems().stream()
                .map(item -> ShipmentRequest.ShipmentItemRequest.builder()
                        .productName(item.getProductName())
                        .productSku(item.getProductSku())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .build())
                .collect(Collectors.toList());

        return ShipmentRequest.builder()
                .orderNumber(order.getOrderNumber())
                .customerName(order.getCustomerName())
                .customerPhone(order.getCustomerPhone())
                .customerPhone2(order.getCustomerPhone2())
                .address(order.getAddress())
                .city(order.getCity())
                .ville(order.getVille())
                .deliveryCityId(order.getDeliveryCityId())
                .zipCode(order.getZipCode())
                .codAmount(order.getTotalAmount())
                .shippingCost(order.getShippingCost())
                .notes(order.getNotes())
                .items(items)
                .exchange(order.isExchange())
                .build();
    }

    private DeliveryShipmentDto toDto(DeliveryShipment shipment, boolean withHistory) {
        List<DeliveryShipmentDto.TrackingEventDto> history = withHistory
                ? shipment.getTrackingHistory().stream()
                    .map(h -> DeliveryShipmentDto.TrackingEventDto.builder()
                            .status(h.getStatus())
                            .description(h.getDescription())
                            .location(h.getLocation())
                            .eventAt(h.getEventAt())
                            .build())
                    .collect(Collectors.toList())
                : null;

        return DeliveryShipmentDto.builder()
                .id(shipment.getId())
                .orderId(shipment.getOrder().getId())
                .orderNumber(shipment.getOrder().getOrderNumber())
                .customerName(shipment.getOrder().getCustomerName())
                .providerId(shipment.getProvider().getId())
                .providerName(shipment.getProvider().getName())
                .trackingNumber(shipment.getTrackingNumber())
                .providerOrderId(shipment.getProviderOrderId())
                .status(shipment.getStatus())
                .statusLabel(shipment.getProviderStatusLabel() != null
                        ? shipment.getProviderStatusLabel()
                        : shipment.getStatus().getLabel())
                .pickupRequested(shipment.isPickupRequested())
                .pickupRequestedAt(shipment.getPickupRequestedAt())
                .shippedAt(shipment.getShippedAt())
                .outForDeliveryAt(shipment.getOutForDeliveryAt())
                .deliveredAt(shipment.getDeliveredAt())
                .returnedAt(shipment.getReturnedAt())
                .returnReceived(shipment.isReturnReceived())
                .returnReceivedAt(shipment.getReturnReceivedAt())
                .returnReceivedNotes(shipment.getReturnReceivedNotes())
                .notes(shipment.getNotes())
                .deliveredPrice(shipment.getDeliveredPrice())
                .returnedPrice(shipment.getReturnedPrice())
                .refusedPrice(shipment.getRefusedPrice())
                .appliedFee(shipment.getAppliedFee())
                .appliedFeeType(shipment.getAppliedFeeType())
                .trackingHistory(history)
                .createdAt(shipment.getCreatedAt())
                .updatedAt(shipment.getUpdatedAt())
                .build();
    }
}
