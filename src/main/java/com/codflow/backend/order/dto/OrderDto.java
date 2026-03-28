package com.codflow.backend.order.dto;

import com.codflow.backend.delivery.enums.ShipmentStatus;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.enums.OrderStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class OrderDto {
    private Long id;
    private String orderNumber;
    private OrderSource source;
    private String customerName;
    private String customerPhone;
    private String customerPhone2;
    private String address;
    private String city;
    private String ville;
    private String deliveryCityId;
    private String zipCode;
    private String notes;
    private BigDecimal subtotal;
    private BigDecimal shippingCost;
    private BigDecimal totalAmount;
    private OrderStatus status;
    private String statusLabel;
    private boolean potentialDuplicate;
    private ShipmentStatus deliveryStatus;
    private String deliveryStatusLabel;
    private String trackingNumber;
    private Long customerId;
    private Long assignedToId;
    private String assignedToName;
    private LocalDateTime assignedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime cancelledAt;
    private String shopifyOrderId;
    private List<OrderItemDto> items;
    private List<OrderStatusHistoryDto> statusHistory;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
