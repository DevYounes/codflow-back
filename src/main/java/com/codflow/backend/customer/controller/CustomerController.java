package com.codflow.backend.customer.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.customer.dto.CustomerDto;
import com.codflow.backend.customer.dto.UpdateCustomerRequest;
import com.codflow.backend.customer.enums.CustomerStatus;
import com.codflow.backend.customer.service.CustomerService;
import com.codflow.backend.order.dto.OrderDto;
import com.codflow.backend.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
@Tag(name = "Customers", description = "Gestion des clients")
public class CustomerController {

    private final CustomerService customerService;
    private final OrderService orderService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT')")
    @Operation(summary = "Liste des clients avec filtres")
    public ResponseEntity<ApiResponse<Page<CustomerDto>>> getCustomers(
            @RequestParam(required = false) CustomerStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "20")   int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        return ResponseEntity.ok(ApiResponse.success(
                customerService.getCustomers(status, search, page, size, sortBy, sortDir)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT')")
    @Operation(summary = "Détail d'un client avec ses statistiques")
    public ResponseEntity<ApiResponse<CustomerDto>> getCustomer(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(customerService.getCustomer(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT')")
    @Operation(summary = "Mettre à jour les infos d'un client")
    public ResponseEntity<ApiResponse<CustomerDto>> updateCustomer(
            @PathVariable Long id,
            @RequestBody UpdateCustomerRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Client mis à jour", customerService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Supprimer un client",
        description = "Suppression définitive du client et de toutes ses commandes."
    )
    public ResponseEntity<ApiResponse<Void>> deleteCustomer(@PathVariable Long id) {
        customerService.deleteCustomer(id);
        return ResponseEntity.ok(ApiResponse.success("Client supprimé"));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT')")
    @Operation(summary = "Changer le statut d'un client (blacklist, non sérieux, fidèle...)")
    public ResponseEntity<ApiResponse<CustomerDto>> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        CustomerStatus status = CustomerStatus.valueOf(body.get("status").toUpperCase());
        String notes = body.get("notes");
        return ResponseEntity.ok(ApiResponse.success(
                "Statut client mis à jour", customerService.updateStatus(id, status, notes)));
    }

    @GetMapping("/{id}/orders")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'AGENT')")
    @Operation(
        summary = "Historique complet des commandes d'un client",
        description = """
            Retourne TOUTES les commandes d'un client, tous agents confondus.
            Contrairement à GET /orders (qui filtre par agent assigné pour les AGENT),
            cet endpoint permet à un agent de consulter l'historique complet d'un client
            afin d'identifier les doublons, les clients non sérieux ou blacklistés avant
            de traiter sa propre commande. Lecture seule.
            """
    )
    public ResponseEntity<ApiResponse<PageResponse<OrderDto>>> getCustomerOrders(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.success(
                orderService.getOrdersByCustomer(id, pageable)));
    }
}
