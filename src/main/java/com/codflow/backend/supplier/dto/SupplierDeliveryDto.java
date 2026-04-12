package com.codflow.backend.supplier.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class SupplierDeliveryDto {
    private Long id;
    private String lotNumber;
    private LocalDate deliveryDate;
    private String notes;
    private List<SupplierDeliveryItemDto> items;
    private LocalDateTime createdAt;
}
