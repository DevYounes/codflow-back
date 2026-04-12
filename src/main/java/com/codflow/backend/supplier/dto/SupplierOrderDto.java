package com.codflow.backend.supplier.dto;

import com.codflow.backend.supplier.enums.SupplierOrderStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class SupplierOrderDto {
    private Long id;
    private String orderNumber;
    private Long supplierId;
    private String supplierName;
    private SupplierOrderStatus status;
    private String statusLabel;
    private LocalDate orderDate;
    private LocalDate expectedDeliveryDate;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private String notes;
    private List<SupplierOrderItemDto> items;
    private List<SupplierPaymentDto> payments;
    private List<SupplierDeliveryDto> deliveries;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
