package com.codflow.backend.importer.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Paramètres pour l'import historique Shopify.
 * Les frais sont utilisés pour créer les shipments des commandes LIVRE / RETOURNE
 * (nécessaire pour que les analytics de charges soient corrects).
 */
@Getter
@Setter
public class HistoricalImportRequest {

    /**
     * Frais de livraison facturés par Ozon pour chaque commande livrée (ex: 25.00 MAD).
     */
    private BigDecimal deliveryFee = BigDecimal.valueOf(25);

    /**
     * Frais de retour facturés par Ozon pour chaque commande retournée/refusée (ex: 10.00 MAD).
     */
    private BigDecimal returnFee = BigDecimal.valueOf(10);
}
