package com.codflow.backend.delivery.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Corps de la requête pour confirmer la réception physique d'un colis retourné.
 */
@Getter
@Setter
@NoArgsConstructor
public class ConfirmReturnRequest {
    /** Notes optionnelles (état du colis, remarques...) */
    private String notes;
}
