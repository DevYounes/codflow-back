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
                orderService.getOrders(status, source, assignedTo, search, from, to, pageable, principal)));
    }

    @GetMapping("/my-orders")
    @PreAuthorize("hasRole('AGENT')")
    @Operation(summary = "Obtenir mes commandes assignées")
    public ResponseEntity<ApiResponse<List<OrderDto>>> getMyOrders(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getMyOrders(principal)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtenir une commande par ID")
    public ResponseEntity<ApiResponse<OrderDto>> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getOrder(id)));
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

    @PostMapping("/bulk-assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Assigner plusieurs commandes à un agent")
    public ResponseEntity<ApiResponse<Void>> bulkAssignOrders(
            @Valid @RequestBody AssignOrderRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        orderService.bulkAssignOrders(request, principal);
        return ResponseEntity.ok(ApiResponse.success("Commandes assignées avec succès"));
    }
}
