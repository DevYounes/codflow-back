package com.codflow.backend.salary.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Création d'une fiche de paie pour un utilisateur sur une période donnée.
 * Le service calcule automatiquement les compteurs et montants à partir
 * de la configuration salariale courante de l'utilisateur.
 */
@Getter
@Setter
public class CreateSalaryPaymentRequest {

    @NotNull(message = "L'utilisateur est obligatoire")
    private Long userId;

    @NotNull(message = "La date de début de période est obligatoire")
    private LocalDate periodStart;

    @NotNull(message = "La date de fin de période est obligatoire")
    private LocalDate periodEnd;

    @PositiveOrZero(message = "La prime doit être positive ou nulle")
    private BigDecimal bonus;

    @PositiveOrZero(message = "La déduction doit être positive ou nulle")
    private BigDecimal deduction;

    private String notes;
}
