package com.codflow.backend.publicapi.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.product.entity.Product;
import com.codflow.backend.product.entity.ProductVariant;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.product.repository.ProductVariantRepository;
import com.codflow.backend.publicapi.dto.PublicProductDto;
import com.codflow.backend.publicapi.dto.PublicProductDto.Variant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Catalogue en lecture seule pour les sites vitrines.
 *
 * <p>Sécurisé par {@code X-API-Key} (voir {@link com.codflow.backend.publicapi.config.ApiKeyAuthenticationFilter}).</p>
 *
 * <p>Expose uniquement :
 * <ul>
 *   <li>Produits actifs</li>
 *   <li>Prix public, nom, description, image</li>
 *   <li>Stock disponible ({@code currentStock - reservedStock})</li>
 *   <li>Variantes actives (couleur / pointure)</li>
 * </ul>
 * Pas de coût de revient, pas de seuil, pas de stock brut.</p>
 */
@RestController
@RequestMapping("/api/v1/public/products")
@RequiredArgsConstructor
@Tag(name = "Public API — Products", description = "Catalogue lecture seule pour les sites vitrines")
public class PublicProductController {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    @GetMapping
    @Operation(summary = "Lister tous les produits actifs avec leurs variantes")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<PublicProductDto>>> listProducts() {
        List<Product> products = productRepository.findByActiveTrue();
        List<PublicProductDto> result = products.stream().map(this::toDto).toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{sku}")
    @Operation(summary = "Obtenir un produit par SKU (actif uniquement)")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<PublicProductDto>> getProduct(@PathVariable String sku) {
        Product p = productRepository.findBySku(sku)
                .filter(Product::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Produit introuvable: " + sku));
        return ResponseEntity.ok(ApiResponse.success(toDto(p)));
    }

    private PublicProductDto toDto(Product p) {
        List<ProductVariant> variants = variantRepository.findByProductIdAndActiveTrue(p.getId());
        List<Variant> vDtos = variants.stream().map(v -> Variant.builder()
                .id(v.getId())
                .variantSku(v.getVariantSku())
                .color(v.getColor())
                .size(v.getSize())
                .price(v.getPriceOverride() != null ? v.getPriceOverride() : p.getPrice())
                .availableStock(v.getAvailableStock())
                .build()).toList();

        return PublicProductDto.builder()
                .id(p.getId())
                .sku(p.getSku())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .imageUrl(p.getImageUrl())
                .availableStock(p.getAvailableStock())
                .variants(vDtos)
                .build();
    }
}
