package com.codflow.backend.analytics.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class ProductPerformanceDto {

    private Long   productId;
    private String productName;
    private String productSku;

    /** Nombre total d'articles commandés (toutes commandes confondues). */
    private long totalQuantityOrdered;

    /** Nombre d'articles dans des commandes LIVRÉES. */
    private long quantityDelivered;

    /** Nombre d'articles dans des commandes RETOURNÉES. */
    private long quantityReturned;

    /** Nombre d'articles dans des commandes annulées / pas sérieux / fake. */
    private long quantityCancelled;

    /** Chiffre d'affaires généré (commandes LIVRE uniquement). */
    private BigDecimal revenue;

    /** Coût total des articles livrés (unitCost × qty). */
    private BigDecimal productCost;

    /** Marge brute = revenue - productCost. */
    private BigDecimal grossMargin;

    /** Taux de livraison = livrés / (livrés + retournés) %. */
    private double deliveryRate;

    /** Panier moyen par commande livrée. */
    private BigDecimal avgOrderValue;
}
