package com.codflow.backend.customer.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.customer.dto.CustomerDto;
import com.codflow.backend.customer.dto.UpdateCustomerRequest;
import com.codflow.backend.customer.enums.CustomerStatus;
import com.codflow.backend.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Liste des clients avec filtres")
    public ResponseEntity<ApiResponse<Page<CustomerDto>>> getCustomers(
            @RequestParam(required = false) CustomerStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "20")   int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(ApiResponse.success(
                customerService.getCustomers(status, search, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Détail d'un client avec ses statistiques")
    public ResponseEntity<ApiResponse<CustomerDto>> getCustomer(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(customerService.getCustomer(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Mettre à jour les infos d'un client")
    public ResponseEntity<ApiResponse<CustomerDto>> updateCustomer(
            @PathVariable Long id,
            @RequestBody UpdateCustomerRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Client mis à jour", customerService.update(id, request)));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Changer le statut d'un client (blacklist, non sérieux, fidèle...)")
    public ResponseEntity<ApiResponse<CustomerDto>> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        CustomerStatus status = CustomerStatus.valueOf(body.get("status").toUpperCase());
        String notes = body.get("notes");
        return ResponseEntity.ok(ApiResponse.success(
                "Statut client mis à jour", customerService.updateStatus(id, status, notes)));
    }
}
