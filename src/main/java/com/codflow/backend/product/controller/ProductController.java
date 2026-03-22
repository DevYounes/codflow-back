package com.codflow.backend.product.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.product.dto.CreateProductRequest;
import com.codflow.backend.product.dto.ProductDto;
import com.codflow.backend.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Gestion des produits")
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Créer un nouveau produit")
    public ResponseEntity<ApiResponse<ProductDto>> createProduct(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Produit créé avec succès", productService.createProduct(request)));
    }

    @GetMapping
    @Operation(summary = "Lister les produits")
    public ResponseEntity<ApiResponse<PageResponse<ProductDto>>> getProducts(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return ResponseEntity.ok(ApiResponse.success(productService.getProducts(search, pageable)));
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Obtenir les produits avec stock faible")
    public ResponseEntity<ApiResponse<List<ProductDto>>> getLowStockProducts() {
        return ResponseEntity.ok(ApiResponse.success(productService.getLowStockProducts()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtenir un produit par ID")
    public ResponseEntity<ApiResponse<ProductDto>> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(productService.getProduct(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Mettre à jour un produit")
    public ResponseEntity<ApiResponse<ProductDto>> updateProduct(@PathVariable Long id,
                                                                  @Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Produit mis à jour", productService.updateProduct(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Désactiver un produit")
    public ResponseEntity<ApiResponse<Void>> deactivateProduct(@PathVariable Long id) {
        productService.deactivateProduct(id);
        return ResponseEntity.ok(ApiResponse.success("Produit désactivé"));
    }
}
