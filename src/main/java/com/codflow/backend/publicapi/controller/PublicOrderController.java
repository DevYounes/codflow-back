package com.codflow.backend.publicapi.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.order.dto.CreateOrderRequest;
import com.codflow.backend.order.dto.CreateOrderRequest.OrderItemRequest;
import com.codflow.backend.order.dto.OrderDto;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.order.service.OrderService;
import com.codflow.backend.publicapi.dto.CastelloOrderRequest;
import com.codflow.backend.publicapi.dto.CastelloOrderRequest.CastelloOrderItemRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Endpoints publics appelés par les sites vitrines (CASTELLO) pour pousser
 * leurs commandes dans CODflow sans authentification JWT (authentification
 * par API key {@code X-API-Key} gérée par {@code ApiKeyAuthenticationFilter}).
 *
 * <p>Les commandes créées ici ont {@code source = CASTELLO_DIRECT} et
 * démarrent en statut {@code NOUVEAU} comme toute autre commande, pour
 * suivre le cycle de vie normal (confirmation téléphone → expédition).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public/orders")
@RequiredArgsConstructor
@Tag(name = "Public API — Orders", description = "Endpoints publics pour les sites vitrines (CASTELLO, etc.)")
public class PublicOrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    @PostMapping("/castello")
    @Operation(
        summary = "Créer une commande CASTELLO",
        description = """
            Reçoit une commande depuis le site vitrine CASTELLO (Next.js).

            Authentification requise : en-tête `X-API-Key` avec la valeur de `app.castello.api-key`.

            Idempotence : le champ `externalRef` (ex. `CAT-20260414ABCD`) est utilisé
            pour détecter les retries réseau. Si une commande avec le même `externalRef`
            existe déjà, elle est renvoyée sans créer de doublon.

            La commande est créée en statut `NOUVEAU` avec `source=CASTELLO_DIRECT` et
            est auto-assignée à un agent via le round-robin.
            """
    )
    public ResponseEntity<ApiResponse<OrderDto>> createCastelloOrder(
            @Valid @RequestBody CastelloOrderRequest request) {

        // Idempotence : si externalRef déjà vu, retourner la commande existante (pas d'erreur)
        Optional<com.codflow.backend.order.entity.Order> existing =
                orderRepository.findByExternalRef(request.getExternalRef());
        if (existing.isPresent()) {
            log.info("CASTELLO duplicate received for externalRef={}, returning existing order {}",
                    request.getExternalRef(), existing.get().getOrderNumber());
            return ResponseEntity.ok(ApiResponse.success(
                    "Commande déjà enregistrée (retry idempotent)",
                    orderService.getOrder(existing.get().getId(), null)));
        }

        CreateOrderRequest createRequest = toCreateOrderRequest(request);

        // principal = null : pas d'utilisateur connecté (appel machine-to-machine).
        // recordStatusChange() et getOrder() tolèrent un principal null.
        OrderDto created = orderService.createOrder(createRequest, null);
        log.info("CASTELLO order created: {} (externalRef={})",
                created.getOrderNumber(), request.getExternalRef());

        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success("Commande CASTELLO enregistrée avec succès", created));
    }

    private CreateOrderRequest toCreateOrderRequest(CastelloOrderRequest src) {
        CreateOrderRequest dst = new CreateOrderRequest();
        dst.setSource(OrderSource.CASTELLO_DIRECT);
        dst.setExternalRef(src.getExternalRef());
        dst.setCustomerName(src.getCustomerName());
        dst.setCustomerPhone(src.getCustomerPhone());
        dst.setCustomerPhone2(src.getCustomerPhone2());
        dst.setAddress(src.getAddress());
        dst.setVille(src.getVille());
        dst.setCity(src.getVille());
        dst.setDeliveryCityId(src.getDeliveryCityId());
        dst.setNotes(src.getNotes());
        dst.setShippingCost(BigDecimal.ZERO);

        List<OrderItemRequest> items = new ArrayList<>();
        for (CastelloOrderItemRequest it : src.getItems()) {
            OrderItemRequest dItem = new OrderItemRequest();
            dItem.setProductId(it.getProductId());
            dItem.setVariantId(it.getVariantId());
            dItem.setProductName(it.getProductName());
            dItem.setProductSku(it.getProductSku());
            dItem.setQuantity(it.getQuantity() != null ? it.getQuantity() : 1);
            dItem.setUnitPrice(it.getUnitPrice());
            items.add(dItem);
        }
        dst.setItems(items);
        return dst;
    }
}
