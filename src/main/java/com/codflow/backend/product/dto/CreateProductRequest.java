package com.codflow.backend.product.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateProductRequest {

    @NotBlank(message = "Le SKU est obligatoire")
    @Size(max = 100)
    private String sku;

    @NotBlank(message = "Le nom est obligatoire")
    private String name;

    private String description;

    @NotNull(message = "Le prix est obligatoire")
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal price;

    private String imageUrl;

    @Min(0)
    private int initialStock = 0;

    @Min(0)
    private int minThreshold = 10;

    private boolean alertEnabled = true;
}
