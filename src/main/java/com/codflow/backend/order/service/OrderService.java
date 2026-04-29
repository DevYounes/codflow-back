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
        order.setDeliveryNotes(request.getDeliveryNotes());
        order.setShippingCost(request.getShippingCost() != null ? request.getShippingCost() : java.math.BigDecimal.ZERO);
        order.setShopifyOrderId(request.getShopifyOrderId());
        order.setExternalRef(request.getExternalRef());
        order.setDeliveryCityId(request.getDeliveryCityId());

        // Si le client est blacklisté, la commande est rejetée d'emblée
        boolean customerBlacklisted = customer != null
                && customer.getStatus() == com.codflow.backend.customer.enums.CustomerStatus.BLACKLISTED;
        if (customerBlacklisted) {
            order.setStatus(OrderStatus.CLIENT_BLACKLISTE);
            order.setCancelledAt(LocalDateTime.now());
        } else {
            order.setStatus(OrderStatus.NOUVEAU);
        }

        // Assignation explicite depuis le body — prioritaire sur le round-robin.
        // Sans ça, RoundRobinAssignmentService.assign() écrase l'assignedTo du body
        // puisqu'il fait setAssignedTo() sans tester l'existant.
        if (request.getAssignedToId() != null) {
            userRepository.findById(request.getAssignedToId()).ifPresent(agent -> {
                order.setAssignedTo(agent);
                order.setAssignedAt(LocalDateTime.now());
            });
        }

        // Add items
        for (CreateOrderRequest.OrderItemRequest itemReq : request.getItems()) {
            order.addItem(buildOrderItem(itemReq));
        }
        order.recalculateTotals();

        Order saved = orderRepository.save(order);

        // Record initial status history
        String initialNote = customerBlacklisted
                ? "Commande rejetée — client blacklisté"
                : "Commande créée";
        recordStatusChange(saved, null, saved.getStatus(), principal, initialNote);

        // Pas de round-robin pour une commande déjà rejetée
        if (saved.getAssignedTo() == null && !customerBlacklisted) {
            roundRobinAssignmentService.assign(saved);
        }

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
        return createFollowUpOrder(sourceOrderId, request, principal, true);
    }

    /**
     * Crée une commande additionnelle à partir d'une commande livrée dont le client
     * est satisfait et souhaite acheter un autre produit.
     *
     * Comportement :
     * - Copie le client, l'adresse, la ville et le deliveryCityId de la commande source
     * - Les articles sont fournis dans la requête (nouveau produit à livrer)
     * - La commande est créée directement en statut CONFIRME
     * - isExchange = false : il s'agit d'une nouvelle commande payante, pas d'un échange
     *   (aucun flag parcel-echange=1 envoyé à Ozon)
     * - Le stock est réservé immédiatement
     * - Accessible à tous les rôles (ADMIN, MANAGER, AGENT, MAGASINIER)
     */
    @Transactional
    public OrderDto createAdditionalOrder(Long sourceOrderId, CreateOrderRequest request, UserPrincipal principal) {
        return createFollowUpOrder(sourceOrderId, request, principal, false);
    }

    /**
     * Logique partagée entre {@link #createExchangeOrder} et {@link #createAdditionalOrder}.
     * La différence tient dans :
     * - le flag isExchange (true = échange Ozon, false = nouvelle commande payante)
     * - le préfixe du numéro de commande ("ECHANGE-" vs COD-YYYYMMDD-XXXXXX standard)
     * - les libellés dans l'historique et les logs
     */
    private OrderDto createFollowUpOrder(Long sourceOrderId,
                                         CreateOrderRequest request,
                                         UserPrincipal principal,
                                         boolean isExchange) {
        Order sourceOrder = getOrderById(sourceOrderId);

        if (sourceOrder.getStatus() != OrderStatus.LIVRE) {
            throw new BusinessException(
                    (isExchange ? "L'échange" : "Une commande additionnelle")
                    + " ne peut être créé que pour une commande livrée (statut actuel: "
                    + sourceOrder.getStatus().getLabel() + ")");
        }

        Order followUp = new Order();
        followUp.setSource(OrderSource.MANUAL);
        followUp.setExchange(isExchange);
        followUp.setSourceOrder(sourceOrder);

        // Client — peut être surchargé par la requête mais par défaut = même client
        followUp.setCustomerName(firstNonBlank(request.getCustomerName(), sourceOrder.getCustomerName()));
        String rawPhone = firstNonBlank(request.getCustomerPhone(), sourceOrder.getCustomerPhone());
        String normalizedPhone = com.codflow.backend.common.util.PhoneNormalizer.normalize(rawPhone);
        followUp.setCustomerPhone(rawPhone);
        followUp.setCustomerPhoneNormalized(normalizedPhone);
        followUp.setCustomerPhone2(request.getCustomerPhone2() != null
                ? request.getCustomerPhone2() : sourceOrder.getCustomerPhone2());

        // Adresse — par défaut = même adresse
        followUp.setAddress(firstNonBlank(request.getAddress(), sourceOrder.getAddress()));
        followUp.setVille(firstNonBlank(request.getVille(), sourceOrder.getVille()));
        followUp.setCity(firstNonBlank(request.getCity(), sourceOrder.getCity()));
        followUp.setDeliveryCityId(request.getDeliveryCityId() != null && !request.getDeliveryCityId().isBlank()
                ? request.getDeliveryCityId() : sourceOrder.getDeliveryCityId());
        followUp.setZipCode(request.getZipCode() != null ? request.getZipCode() : sourceOrder.getZipCode());
        followUp.setNotes(request.getNotes());
        followUp.setShippingCost(request.getShippingCost() != null
                ? request.getShippingCost() : java.math.BigDecimal.ZERO);

        // Numéro de commande : préfixé pour les échanges, format standard sinon
        String orderNumber;
        if (isExchange) {
            orderNumber = "ECHANGE-" + sourceOrder.getOrderNumber();
            if (orderRepository.existsByOrderNumber(orderNumber)) {
                orderNumber = "ECHANGE-" + sourceOrder.getOrderNumber() + "-"
                        + java.util.UUID.randomUUID().toString().substring(0, 4).toUpperCase();
            }
        } else {
            orderNumber = generateOrderNumber(request);
        }
        followUp.setOrderNumber(orderNumber);

        // Lier au customer existant
        followUp.setCustomer(sourceOrder.getCustomer());

        // Assignation : request.assignedToId > créateur (principal) > assigné de la commande source
        Long assignedToId = request.getAssignedToId() != null
                ? request.getAssignedToId()
                : (principal != null ? principal.getId() : null);
        if (assignedToId != null) {
            userRepository.findById(assignedToId).ifPresent(followUp::setAssignedTo);
        }

        // Articles de la nouvelle commande
        for (CreateOrderRequest.OrderItemRequest itemReq : request.getItems()) {
            followUp.addItem(buildOrderItem(itemReq));
        }
        followUp.recalculateTotals();

        // Démarrer directement en CONFIRME
        followUp.setStatus(OrderStatus.CONFIRME);
        followUp.setConfirmedAt(LocalDateTime.now());

        Order saved = orderRepository.save(followUp);

        // Vérifier le stock avant de réserver (bloque la création si rupture)
        checkStockAvailability(saved);

        // Snapshot des coûts AVANT les opérations stock (entités encore MANAGED)
        snapshotUnitCosts(saved);
        saved.setStockReserved(true);

        // Historique : NOUVEAU → CONFIRME
        String typeLabel = isExchange ? "Échange" : "Commande additionnelle";
        recordStatusChange(saved, null, OrderStatus.NOUVEAU, principal,
                typeLabel + " créée depuis commande " + sourceOrder.getOrderNumber());
        recordStatusChange(saved, OrderStatus.NOUVEAU, OrderStatus.CONFIRME, principal,
                typeLabel + " confirmée automatiquement");

        // Persister l'ordre pendant qu'il est encore MANAGED (avant que les
        // opérations stock ne vident le L1 cache via clearAutomatically=true)
        orderRepository.saveAndFlush(saved);

        // Réservation stock (peut vider le L1 cache via clearAutomatically=true)
        reserveStockForOrder(saved);

        log.info("{} order created: {} from source {} (exchange={})",
                isExchange ? "Exchange" : "Additional",
                saved.getOrderNumber(), sourceOrder.getOrderNumber(), isExchange);
        // Rechargement frais (L1 cache potentiellement vidé par les opérations stock)
        return toDto(getOrderById(saved.getId()), true);
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

        // ── Pré-calcul des opérations stock nécessaires (avant toute modification) ──
        // On lit l'état courant de l'ordre AVANT de le modifier pour décider quelles
        // opérations stock exécuter, et on évite ainsi tout état incohérent.
        boolean shouldReserve   = newStatus.isConfirmed() && order.getConfirmedAt() == null && !order.isStockReserved();
        boolean shouldDeduct    = newStatus.isDelivered()  && !order.isStockDeducted() && order.isStockReserved();
        boolean shouldRelease   = newStatus == OrderStatus.ANNULE  && order.isStockReserved();
        // deferReturnStock = true quand c'est DeliveryService qui appelle :
        // le stock sera libéré à la confirmation physique (confirmReturnReceived), pas maintenant.
        boolean shouldRestoreReserve  = newStatus == OrderStatus.RETOURNE && order.isStockReserved()
                && !request.isDeferReturnStock();
        boolean shouldRestoreDeducted = newStatus == OrderStatus.RETOURNE && !order.isStockReserved()
                && order.isStockDeducted() && !request.isDeferReturnStock();

        // ── Vérification stock AVANT toute modification ──────────────────────────
        if (shouldReserve) {
            checkStockAvailability(order);
        }

        // ── Modification des champs de l'ordre ──────────────────────────────────
        order.setStatus(newStatus);

        // deliveryCityId peut être ajouté/corrigé à chaque (re)confirmation,
        // pas seulement à la première : un agent peut remettre une commande à
        // NOUVEAU pour la corriger puis la re-confirmer avec la bonne ville Ozon.
        if (newStatus.isConfirmed()
                && request.getDeliveryCityId() != null && !request.getDeliveryCityId().isBlank()) {
            order.setDeliveryCityId(request.getDeliveryCityId());
        }

        if (newStatus.isConfirmed() && order.getConfirmedAt() == null) {
            order.setConfirmedAt(LocalDateTime.now());
            if (shouldReserve) {
                order.setStockReserved(true);
            }
            // Snapshot des coûts ICI pendant que les entités sont encore MANAGED
            // (avant que clearAutomatically des opérations stock ne vide le L1 cache)
            snapshotUnitCosts(order);
        }

        if (newStatus.isDelivered() && !order.isStockDeducted()) {
            if (shouldDeduct) order.setStockReserved(false);
            order.setStockDeducted(true);
        }

        if (newStatus.isCancelled() && order.getCancelledAt() == null) {
            order.setCancelledAt(LocalDateTime.now());
        }
        if (shouldRelease) {
            order.setStockReserved(false);
        }

        if (newStatus == OrderStatus.RETOURNE && !request.isDeferReturnStock()) {
            if (shouldRestoreReserve)  order.setStockReserved(false);
            if (shouldRestoreDeducted) order.setStockDeducted(false);
        }

        recordStatusChange(order, previousStatus, newStatus, principal, request.getNotes());

        // ── Persistance de l'ordre pendant qu'il est encore MANAGED ─────────────
        // saveAndFlush AVANT les opérations stock : les @Modifying avec
        // clearAutomatically=true vident le L1 cache Hibernate ; si on sauvegardait
        // après, merge() sur une entité détachée pourrait réécrire un état périmé
        // en DB (notamment currentStock via le dirty-check ou les cascades).
        orderRepository.saveAndFlush(order);

        // ── Opérations stock (peuvent vider le L1 cache) ────────────────────────
        if (shouldReserve)         reserveStockForOrder(order);
        if (shouldDeduct)          finalizeStockDeduction(order);
        if (shouldRelease)         releaseReservationForOrder(order, "Commande annulée");
        if (shouldRestoreReserve)  releaseReservationForOrder(order, "Colis retourné avant livraison");
        if (shouldRestoreDeducted) restoreStockForOrder(order, "Colis retourné après livraison");

        // ── Rechargement frais (L1 cache potentiellement vidé) ──────────────────
        return toDto(getOrderById(orderId), true);
    }

    /**
     * Applique les opérations stock suite à la confirmation physique d'un retour.
     * Appelé par DeliveryService.confirmReturnReceived() — jamais depuis updateStatus().
     *
     * Deux cas :
     *  - stockReserved = true  → colis retourné avant livraison : libère la réservation
     *  - stockDeducted = true  → colis retourné après livraison  : restaure le stock physique
     *
     * Respecte le pattern saveAndFlush AVANT les opérations @Modifying stock
     * pour éviter l'écrasement du L1 cache Hibernate (cf. CLAUDE.md).
     */
    @Transactional
    public void processPhysicalReturn(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Commande", orderId));

        boolean shouldRestoreReserve  = order.isStockReserved();
        boolean shouldRestoreDeducted = !order.isStockReserved() && order.isStockDeducted();

        if (!shouldRestoreReserve && !shouldRestoreDeducted) {
            log.debug("[RETOUR PHYSIQUE] Commande {} — aucune opération stock nécessaire (déjà traité ou pas de stock)",
                    order.getOrderNumber());
            return;
        }

        // Mise à jour des flags avant saveAndFlush
        if (shouldRestoreReserve)  order.setStockReserved(false);
        if (shouldRestoreDeducted) order.setStockDeducted(false);
        orderRepository.saveAndFlush(order);

        // Opérations stock (peuvent vider le L1 cache via clearAutomatically)
        if (shouldRestoreReserve) {
            releaseReservationForOrder(order, "Retour physique confirmé par le marchand");
            log.info("[RETOUR PHYSIQUE] Commande {} — réservation libérée (colis retourné avant livraison)",
                    order.getOrderNumber());
        } else {
            restoreStockForOrder(order, "Retour physique confirmé par le marchand");
            log.info("[RETOUR PHYSIQUE] Commande {} — stock restauré (colis retourné après livraison)",
                    order.getOrderNumber());
        }
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
        order.setDeliveryNotes(request.getDeliveryNotes());
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
                OrderItem item = buildOrderItem(itemReq);
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
     * Liste toutes les commandes d'un client (tous agents confondus).
     *
     * Contrairement à {@link #getOrders}, ce flux NE filtre PAS par agent —
     * un agent peut ainsi consulter l'historique complet d'un client pour
     * identifier les doublons, les clients non sérieux ou blacklistés
     * avant de traiter sa propre commande.
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderDto> getOrdersByCustomer(Long customerId, Pageable pageable) {
        Page<Order> page = orderRepository.findByCustomerId(customerId, pageable);
        return PageResponse.of(page.map(o -> toDto(o, false)));
    }

    /**
     * Expands frontend-friendly status aliases to actual OrderStatus values.
     * CANCELLED maps to the full cancelled group (ANNULE, PAS_SERIEUX, FAKE_ORDER, CLIENT_BLACKLISTE).
     * RETURNED maps to RETOURNE.
     */
    private Collection<OrderStatus> expandStatusAliases(Collection<String> rawStatuses) {
        if (rawStatuses == null || rawStatuses.isEmpty()) return null;
        List<OrderStatus> result = new ArrayList<>();
        for (String s : rawStatuses) {
            switch (s.toUpperCase()) {
                case "CANCELLED" -> {
                    result.add(OrderStatus.ANNULE);
                    result.add(OrderStatus.PAS_SERIEUX);
                    result.add(OrderStatus.FAKE_ORDER);
                    result.add(OrderStatus.CLIENT_BLACKLISTE);
                }
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
            // existsByOrderNumberIncludingDeleted bypasse le @SQLRestriction : une commande
            // soft-deleted occupe toujours son order_number côté DB (contrainte unique).
            // Sans ce check on aurait un duplicate key SQL au moment de l'INSERT.
            if (!orderRepository.existsByOrderNumberIncludingDeleted(request.getOrderNumber())) {
                return request.getOrderNumber();
            }
            // Shopify imports: collision possible si une commande a été soft-deleted ou si un
            // agent a manuellement créé une commande avec le même numéro.
            // On ajoute un suffixe unique plutôt que d'échouer — pour ne jamais perdre une commande Shopify.
            if (request.getSource() == OrderSource.SHOPIFY) {
                String candidate = request.getOrderNumber() + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
                log.warn("Order number '{}' already exists — using '{}' for Shopify import {}",
                        request.getOrderNumber(), candidate, request.getShopifyOrderId());
                return candidate;
            }
            throw new BusinessException("Ce numéro de commande existe déjà: " + request.getOrderNumber());
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

    /**
     * Vérifie que chaque article de la commande a un stock disponible suffisant.
     * Recharge les entités depuis la DB pour avoir les valeurs à jour (après d'éventuels
     * incréments atomiques par d'autres transactions).
     * Lance BusinessException si au moins un produit est en rupture.
     */
    private void checkStockAvailability(Order order) {
        List<String> ruptures = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            if (item.getProduct() == null) continue; // sans produit lié, pas de contrôle de stock

            int available;
            String label;
            if (item.getVariant() != null) {
                // Recharger la variante pour avoir le stock à jour
                available = productVariantRepository.findById(item.getVariant().getId())
                        .map(com.codflow.backend.product.entity.ProductVariant::getAvailableStock)
                        .orElse(0);
                label = item.getProductName();
            } else {
                // Recharger le produit pour avoir le stock à jour
                available = productRepository.findById(item.getProduct().getId())
                        .map(com.codflow.backend.product.entity.Product::getAvailableStock)
                        .orElse(0);
                label = item.getProductName();
            }

            if (available < item.getQuantity()) {
                ruptures.add(String.format("'%s' : %d disponible(s), %d demandé(s)",
                        label, available, item.getQuantity()));
            }
        }
        if (!ruptures.isEmpty()) {
            throw new BusinessException(
                    "Stock insuffisant — impossible de confirmer : " + String.join(" | ", ruptures));
        }
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

    @Transactional
    public void deleteOrder(Long id) {
        Order order = getOrderById(id);

        if (order.isStockReserved()) {
            throw new BusinessException(
                "Impossible de supprimer cette commande : du stock est encore réservé. " +
                "Annulez la commande avant de la supprimer.");
        }
        if (order.isStockDeducted()) {
            throw new BusinessException(
                "Impossible de supprimer une commande livrée dont le stock a déjà été déduit.");
        }
        if (order.getStatus() == OrderStatus.LIVRE) {
            throw new BusinessException(
                "Impossible de supprimer une commande avec le statut LIVRÉ.");
        }

        order.setDeleted(true);
        order.setDeletedAt(LocalDateTime.now());
        orderRepository.save(order);
        log.info("[ORDER-DELETE] Commande {} supprimée (soft delete)", order.getOrderNumber());
    }

    private Order getOrderById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Commande", id));
    }

    private OrderItem buildOrderItem(CreateOrderRequest.OrderItemRequest itemReq) {
        OrderItem item = new OrderItem();
        item.setProductName(itemReq.getProductName());
        item.setProductSku(itemReq.getProductSku());
        item.setQuantity(itemReq.getQuantity());
        item.setUnitPrice(itemReq.getUnitPrice());
        item.setVariantColor(itemReq.getVariantColor());
        item.setVariantSize(itemReq.getVariantSize());
        if (itemReq.getProductId() != null) {
            productRepository.findById(itemReq.getProductId()).ifPresent(item::setProduct);
        }
        if (itemReq.getVariantId() != null) {
            productVariantRepository.findById(itemReq.getVariantId()).ifPresent(v -> {
                item.setVariant(v);
                // Variant overrides manual color/size — source of truth if linked
                item.setVariantColor(v.getColor());
                item.setVariantSize(v.getSize());
                if (v.getPriceOverride() != null && itemReq.getUnitPrice() == null) {
                    item.setUnitPrice(v.getPriceOverride());
                }
            });
        }
        item.calculateTotalPrice();
        return item;
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
                    .variantColor(item.getVariantColor())
                    .variantSize(item.getVariantSize())
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
                .deliveryNotes(order.getDeliveryNotes())
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
