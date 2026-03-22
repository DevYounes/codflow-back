package com.codflow.backend.product.service;

import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.product.dto.CreateProductRequest;
import com.codflow.backend.product.dto.ProductDto;
import com.codflow.backend.product.entity.Product;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.product.repository.ProductSpecification;
import com.codflow.backend.stock.entity.StockMovement;
import com.codflow.backend.stock.enums.MovementType;
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
    private final StockMovementRepository stockMovementRepository;

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

    public ProductDto toDto(Product product) {
        return ProductDto.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .imageUrl(product.getImageUrl())
                .active(product.isActive())
                .currentStock(product.getCurrentStock())
                .minThreshold(product.getMinThreshold())
                .alertEnabled(product.isAlertEnabled())
                .lowStock(product.getCurrentStock() <= product.getMinThreshold() && product.getCurrentStock() > 0)
                .outOfStock(product.getCurrentStock() == 0)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
