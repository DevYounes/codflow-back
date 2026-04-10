package com.codflow.backend.supplier.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class SupplierDeliveryItemDto {
    private Long id;
    private Long orderItemId;
    private String productName;
    private String productSku;
    private int quantityReceived;
    private BigDecimal unitCost;
}
