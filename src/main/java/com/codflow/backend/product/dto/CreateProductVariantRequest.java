package com.codflow.backend.product.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateProductVariantRequest {
    private String color;
    private String size;
    private String variantSku;
    private BigDecimal priceOverride;
    private int currentStock = 0;
}
