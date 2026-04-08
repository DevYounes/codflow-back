package com.codflow.backend.product.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class ProductDto {
    private Long id;
    private String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private BigDecimal costPrice;
    private String imageUrl;
    private boolean active;
    private int currentStock;
    private int reservedStock;
    private int availableStock;
    private int minThreshold;
    private boolean alertEnabled;
    private boolean lowStock;
    private boolean outOfStock;
    private List<ProductVariantDto> variants;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
