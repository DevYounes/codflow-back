package com.codflow.backend.stock.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class StockArrivalDto {
    private Long id;
    private String reference;
    private Long productId;
    private String productName;
    private String productSku;
    private LocalDate arrivedAt;
    private String notes;
    private List<ArrivalItemDto> items;
    private int totalQuantity;
    private LocalDateTime createdAt;

    @Getter
    @Builder
    public static class ArrivalItemDto {
        private Long id;
        private Long variantId;
        private String variantLabel; // "Taille 42 / Rouge"
        private int quantity;
        private BigDecimal unitCost;
    }
}
