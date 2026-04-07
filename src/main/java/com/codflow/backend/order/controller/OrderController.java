package com.codflow.backend.order.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.order.dto.*;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.enums.OrderStatus;
import com.codflow.backend.order.service.OrderService;
import com.codflow.backend.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Gestion des commandes COD")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Créer une commande manuellement")
    public ResponseEntity<ApiResponse<OrderDto>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Commande créée avec succès", orderService.createOrder(request, principal)));
    }

    @GetMapping
    @Operation(summary = "Lister les commandes avec filtres")
    public ResponseEntity<ApiResponse<PageResponse<OrderDto>>> getOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) OrderSource source,
            @RequestParam(required = false) Long assignedTo,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @AuthenticationPrincipal UserPrincipal principal) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        PageRequest pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(ApiResponse.success(
                orderService.getOrders(status, statuses, source, assignedTo, search, from, to, pageable, principal)));
    }

    @GetMapping("/my-orders")
    @PreAuthorize("hasRole('AGENT')")
    @Operation(summary = "Obtenir mes commandes assignées")
    public ResponseEntity<ApiResponse<List<OrderDto>>> getMyOrders(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getMyOrders(principal)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtenir une commande par ID")
    public ResponseEntity<ApiResponse<OrderDto>> getOrder(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getOrder(id, principal)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier les informations d'une commande")
    public ResponseEntity<ApiResponse<OrderDto>> updateOrder(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrderRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Commande mise à jour", orderService.updateOrder(id, request, principal)));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Mettre à jour le statut d'une commande")
    public ResponseEntity<ApiResponse<OrderDto>> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrderStatusRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Statut mis à jour", orderService.updateStatus(id, request, principal)));
    }

    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Assigner une commande à un agent")
    public ResponseEntity<ApiResponse<OrderDto>> assignOrder(
            @PathVariable Long id,
            @Valid @RequestBody AssignOrderRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success("Commande assignée", orderService.assignOrder(id, request, principal)));
    }

    @GetMapping("/count-by-status")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Nombre de commandes groupé par statut")
    public ResponseEntity<ApiResponse<Map<String, Long>>> countByStatus() {
        return ResponseEntity.ok(ApiResponse.success(orderService.countByStatus()));
    }

    @PostMapping("/bulk-assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Assigner plusieurs commandes à un agent")
    public ResponseEntity<ApiResponse<Void>> bulkAssignOrders(
            @Valid @RequestBody AssignOrderRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        orderService.bulkAssignOrders(request, principal);
        return ResponseEntity.ok(ApiResponse.success("Commandes assignées avec succès"));
    }

    @PostMapping("/{id}/exchange")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT')")
    @Operation(
        summary = "Créer une commande d'échange depuis une commande livrée",
        description = """
            Crée une nouvelle commande d'échange à partir d'une commande livrée (LIVRE).
            - Copie automatiquement le client, l'adresse et la ville de la commande source
            - Les articles sont le nouveau produit envoyé en échange (obligatoire)
            - La commande démarre directement en statut CONFIRME
            - Le flag is_exchange=true est envoyé à Ozon Express (parcel-echange=1)
              afin que le livreur sache qu'il doit reprendre l'ancien colis
            - Le stock du nouveau produit est réservé immédiatement
            """
    )
    public ResponseEntity<ApiResponse<OrderDto>> createExchangeOrder(
            @PathVariable Long id,
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Commande d'échange créée avec succès",
                        orderService.createExchangeOrder(id, request, principal)));
    }
}
