package com.codflow.backend.supplier.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSupplierRequest {
    @NotBlank
    private String name;
    private String phone;
    @Email
    private String email;
    private String address;
    private String notes;
}
