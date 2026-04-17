package com.codflow.backend.product.enums;

import lombok.Getter;

/**
 * Distingue les produits vendus aux clients (PRODUIT) des consommables internes
 * (CONSOMMABLE) tels que les emballages, étiquettes, etc.
 *
 * Les CONSOMMABLE n'ont pas de variantes — leur stock est géré directement
 * sur Product.currentStock.
 */
@Getter
public enum ProductType {
    PRODUIT("Produit"),
    CONSOMMABLE("Consommable");

    private final String label;

    ProductType(String label) {
        this.label = label;
    }
}
