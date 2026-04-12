package com.codflow.backend.salary.enums;

import lombok.Getter;

@Getter
public enum SalaryType {
    FIXE("Salaire fixe"),
    COMMISSION("Commission seule"),
    FIXE_PLUS_COMMISSION("Salaire fixe + commission");

    private final String label;

    SalaryType(String label) {
        this.label = label;
    }
}
