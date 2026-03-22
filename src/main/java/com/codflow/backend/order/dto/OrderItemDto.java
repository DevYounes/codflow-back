package com.codflow.backend.order.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class OrderItemDto {
    private Long id;
    private Long productId;
    private String productName;
    private String productSku;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
}
