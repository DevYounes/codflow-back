package com.codflow.backend.order.dto;

import com.codflow.backend.order.enums.OrderStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class OrderStatusHistoryDto {
    private Long id;
    private OrderStatus fromStatus;
    private String fromStatusLabel;
    private OrderStatus toStatus;
    private String toStatusLabel;
    private String changedByName;
    private String notes;
    private LocalDateTime createdAt;
}
