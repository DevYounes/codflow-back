package com.codflow.backend.salary.enums;

import lombok.Getter;

@Getter
public enum SalaryPaymentStatus {
    BROUILLON("Brouillon"),
    PAYE("Payé"),
    ANNULE("Annulé");

    private final String label;

    SalaryPaymentStatus(String label) {
        this.label = label;
    }
}
