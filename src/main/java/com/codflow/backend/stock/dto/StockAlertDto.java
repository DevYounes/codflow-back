package com.codflow.backend.stock.dto;

import com.codflow.backend.stock.enums.AlertType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class StockAlertDto {
    private Long id;
    private Long productId;
    private String productName;
    private String productSku;
    private AlertType alertType;
    private int threshold;
    private int currentLevel;
    private boolean resolved;
    private LocalDateTime resolvedAt;
    private String resolvedByName;
    private LocalDateTime createdAt;
}
