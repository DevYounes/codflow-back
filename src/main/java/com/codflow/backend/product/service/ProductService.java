package com.codflow.backend.product.service;

import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.product.dto.CreateProductRequest;
import com.codflow.backend.product.dto.CreateProductVariantRequest;
import com.codflow.backend.product.dto.ProductDto;
import com.codflow.backend.product.dto.ProductVariantDto;
import com.codflow.backend.product.entity.Product;
import com.codflow.backend.product.entity.ProductVariant;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.product.repository.ProductSpecification;
import com.codflow.backend.product.repository.ProductVariantRepository;
import com.codflow.backend.order.repository.OrderItemRepository;
import com.codflow.backend.stock.entity.StockMovement;
import com.codflow.backend.stock.enums.MovementType;
import com.codflow.backend.stock.repository.StockAlertRepository;
import com.codflow.backend.stock.repository.StockArrivalItemRepository;
import com.codflow.backend.stock.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final StockMovementRepository stockMovementRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockAlertRepository stockAlertRepository;
    private final StockArrivalItemRepository stockArrivalItemRepository;

    @Transactional
    public ProductDto createProduct(CreateProductRequest request) {
        if (productRepository.existsBySku(request.getSku())) {
            throw new BusinessException("Un produit avec ce SKU existe déjà: " + request.getSku());
        }
        Product product = new Product();
        product.setSku(request.getSku());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setCostPrice(request.getCostPrice());
        product.setImageUrl(request.getImageUrl());
        product.setCurrentStock(request.getInitialStock());
        product.setMinThreshold(request.getMinThreshold());
        product.setAlertEnabled(request.isAlertEnabled());
        product = productRepository.save(product);

        if (request.getInitialStock() > 0) {
            StockMovement movement = new StockMovement();
            movement.setProduct(product);
            movement.setMovementType(MovementType.IN);
            movement.setQuantity(request.getInitialStock());
            movement.setPreviousStock(0);
            movement.setNewStock(request.getInitialStock());
            movement.setReason("Stock initial à la création du produit");
            stockMovementRepository.save(movement);
        }
        return toDto(product);
    }

    @Transactional
    public ProductDto updateProduct(Long id, CreateProductRequest request) {
        Product product = getProductById(id);
        if (!product.getSku().equals(request.getSku()) && productRepository.existsBySku(request.getSku())) {
            throw new BusinessException("Un produit avec ce SKU existe déjà: " + request.getSku());
        }
        if (StringUtils.hasText(request.getSku())) product.setSku(request.getSku());
        if (StringUtils.hasText(request.getName())) product.setName(request.getName());
        if (request.getDescription() != null) product.setDescription(request.getDescription());
        if (request.getPrice() != null) product.setPrice(request.getPrice());
        if (request.getCostPrice() != null) product.setCostPrice(request.getCostPrice());
        if (request.getImageUrl() != null) product.setImageUrl(request.getImageUrl());
        product.setMinThreshold(request.getMinThreshold());
        product.setAlertEnabled(request.isAlertEnabled());
        return toDto(productRepository.save(product));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductDto> getProducts(String search, Pageable pageable) {
        Page<Product> page = productRepository.findAll(ProductSpecification.withFilters(search), pageable);
        return PageResponse.of(page.map(this::toDto));
    }

    @Transactional(readOnly = true)
    public ProductDto getProduct(Long id) {
        return toDto(getProductById(id));
    }

    @Transactional(readOnly = true)
    public List<ProductDto> getLowStockProducts() {
        return productRepository.findLowStockProducts().stream().map(this::toDto).toList();
    }

    @Transactional
    public void deactivateProduct(Long id) {
        Product product = getProductById(id);
        product.setActive(false);
        productRepository.save(product);
    }

    public Product getProductById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produit", id));
    }

    @Transactional
    public ProductVariantDto addVariant(Long productId, CreateProductVariantRequest request) {
        Product product = getProductById(productId);
        if (productVariantRepository.existsByProductIdAndColorAndSize(productId, request.getColor(), request.getSize())) {
            throw new BusinessException("Une variante avec cette couleur et taille existe déjà");
        }
        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setColor(request.getColor());
        variant.setSize(request.getSize());
        variant.setVariantSku(request.getVariantSku());
        variant.setPriceOverride(request.getPriceOverride());
        variant.setCostPrice(request.getCostPrice());
        variant.setCurrentStock(request.getCurrentStock());
        return toVariantDto(productVariantRepository.save(variant));
    }

    @Transactional(readOnly = true)
    public List<ProductVariantDto> getVariants(Long productId) {
        getProductById(productId); // ensure product exists
        return productVariantRepository.findByProductIdAndActiveTrue(productId)
                .stream().map(this::toVariantDto).toList();
    }

    @Transactional
    public void deleteVariant(Long variantId) {
        ProductVariant variant = productVariantRepository.findById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Variante", variantId));

        if (orderItemRepository.existsByVariantId(variantId)) {
            throw new BusinessException(
                    "Impossible de supprimer cette variante : elle est liée à des commandes existantes. Désactivez-la à la place.");
        }

        // Clean up FKs before hard delete
        stockAlertRepository.deleteByVariantId(variantId);
        stockArrivalItemRepository.nullifyVariant(variantId);

        productVariantRepository.delete(variant);
    }

    public ProductDto toDto(Product product) {
        List<ProductVariantDto> variants = productVariantRepository
                .findByProductIdAndActiveTrue(product.getId())
                .stream().map(this::toVariantDto).toList();

        return ProductDto.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .costPrice(product.getCostPrice())
                .imageUrl(product.getImageUrl())
                .active(product.isActive())
                .currentStock(product.getCurrentStock())
                .minThreshold(product.getMinThreshold())
                .alertEnabled(product.isAlertEnabled())
                .lowStock(product.getCurrentStock() <= product.getMinThreshold() && product.getCurrentStock() > 0)
                .outOfStock(product.getCurrentStock() == 0)
                .variants(variants)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    private ProductVariantDto toVariantDto(ProductVariant v) {
        return ProductVariantDto.builder()
                .id(v.getId())
                .productId(v.getProduct().getId())
                .color(v.getColor())
                .size(v.getSize())
                .variantSku(v.getVariantSku())
                .priceOverride(v.getPriceOverride())
                .costPrice(v.getCostPrice())
                .currentStock(v.getCurrentStock())
                .active(v.isActive())
                .createdAt(v.getCreatedAt())
                .build();
    }
}
