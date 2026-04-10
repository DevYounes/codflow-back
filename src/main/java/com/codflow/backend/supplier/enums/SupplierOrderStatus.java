package com.codflow.backend.supplier.enums;

import lombok.Getter;

@Getter
public enum SupplierOrderStatus {
    BROUILLON("Brouillon"),
    CONFIRME("Confirmé"),
    EN_COURS("En cours de réception"),
    COMPLETE("Complété"),
    ANNULE("Annulé");

    private final String label;

    SupplierOrderStatus(String label) {
        this.label = label;
    }
}
