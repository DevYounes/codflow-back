package com.codflow.backend.supplier.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class SupplierOrderItemDto {
    private Long id;
    private Long productId;
    private Long variantId;
    private String productName;
    private String productSku;
    private int quantityOrdered;
    private int quantityReceived;
    private int remainingQuantity;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
}
