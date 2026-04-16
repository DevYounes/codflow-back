package com.codflow.backend.publicapi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Vue "sites vitrines" d'un produit CODflow.
 *
 * <p>Ne contient PAS les données sensibles (coût de revient, seuils d'alerte,
 * stock réservé brut) : seulement ce qu'un site public a besoin d'afficher.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicProductDto {
    private Long id;
    private String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private String imageUrl;
    private Integer availableStock;
    private List<Variant> variants;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Variant {
        private Long id;
        private String variantSku;
        private String color;
        private String size;
        private BigDecimal price;
        private Integer availableStock;
    }
}
