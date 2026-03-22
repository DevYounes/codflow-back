package com.codflow.backend.stock.dto;

import com.codflow.backend.stock.enums.MovementType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class StockMovementDto {
    private Long id;
    private Long productId;
    private String productName;
    private String productSku;
    private MovementType movementType;
    private int quantity;
    private int previousStock;
    private int newStock;
    private String reason;
    private String referenceType;
    private Long referenceId;
    private String createdByName;
    private LocalDateTime createdAt;
}
