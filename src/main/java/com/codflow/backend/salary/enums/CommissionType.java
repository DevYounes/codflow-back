package com.codflow.backend.salary.enums;

import lombok.Getter;

@Getter
public enum CommissionType {
    PAR_CONFIRME("Par commande confirmée"),
    PAR_LIVRE("Par commande livrée"),
    CONFIRME_ET_LIVRE("Confirmé + Livré");

    private final String label;

    CommissionType(String label) {
        this.label = label;
    }
}
