package com.codflow.backend.supplier.enums;

import lombok.Getter;

@Getter
public enum PaymentMethod {
    ESPECES("Espèces"),
    VIREMENT("Virement bancaire"),
    CHEQUE("Chèque"),
    VIREMENT_INSTANTANE("Virement instantané");

    private final String label;

    PaymentMethod(String label) {
        this.label = label;
    }
}
