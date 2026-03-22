package com.codflow.backend.product.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class ProductVariantDto {
    private Long id;
    private Long productId;
    private String color;
    private String size;
    private String variantSku;
    private BigDecimal priceOverride;
    private int currentStock;
    private boolean active;
    private LocalDateTime createdAt;
}
