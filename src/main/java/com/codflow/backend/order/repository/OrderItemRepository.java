package com.codflow.backend.order.repository;

import com.codflow.backend.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrderId(Long orderId);

    boolean existsByVariantId(Long variantId);

    /**
     * Tous les items non encore liés à un produit (à relier via le backfill).
     * Le JOIN avec Order déclenche le @SQLRestriction("deleted = false") et
     * exclut donc les items dont la commande a été soft-deleted — évite un
     * EntityNotFoundException lors du lazy-load de item.order dans le backfill.
     */
    @Query("SELECT i FROM OrderItem i JOIN i.order o WHERE i.product IS NULL")
    List<OrderItem> findByProductIsNull();

    /**
     * Agrégation des ventes par produit : qty commandée, livrée, retournée, annulée,
     * CA et coût — toutes dates confondues.
     *
     * Retourne Object[] : [productId, productName, productSku,
     *   qtyOrdered, qtyDelivered, qtyReturned, qtyCancelled, revenue, cost]
     */
    @Query("""
        SELECT
            i.product.id,
            i.product.name,
            i.product.sku,
            SUM(i.quantity),
            SUM(CASE WHEN i.order.status = 'LIVRE'    THEN i.quantity ELSE 0 END),
            SUM(CASE WHEN i.order.status = 'RETOURNE' THEN i.quantity ELSE 0 END),
            SUM(CASE WHEN i.order.status IN ('ANNULE','PAS_SERIEUX','FAKE_ORDER')
                                                       THEN i.quantity ELSE 0 END),
            SUM(CASE WHEN i.order.status = 'LIVRE'
                     THEN i.totalPrice ELSE 0 END),
            SUM(CASE WHEN i.order.status = 'LIVRE' AND i.unitCost IS NOT NULL
                     THEN i.unitCost * i.quantity ELSE 0 END)
        FROM OrderItem i
        WHERE i.product IS NOT NULL
        GROUP BY i.product.id, i.product.name, i.product.sku
        ORDER BY SUM(CASE WHEN i.order.status = 'LIVRE' THEN i.quantity ELSE 0 END) DESC
        """)
    List<Object[]> aggregateByProduct();

    /**
     * Même agrégation avec filtre de dates sur la date de création de la commande.
     */
    @Query("""
        SELECT
            i.product.id,
            i.product.name,
            i.product.sku,
            SUM(i.quantity),
            SUM(CASE WHEN i.order.status = 'LIVRE'    THEN i.quantity ELSE 0 END),
            SUM(CASE WHEN i.order.status = 'RETOURNE' THEN i.quantity ELSE 0 END),
            SUM(CASE WHEN i.order.status IN ('ANNULE','PAS_SERIEUX','FAKE_ORDER')
                                                       THEN i.quantity ELSE 0 END),
            SUM(CASE WHEN i.order.status = 'LIVRE'
                     THEN i.totalPrice ELSE 0 END),
            SUM(CASE WHEN i.order.status = 'LIVRE' AND i.unitCost IS NOT NULL
                     THEN i.unitCost * i.quantity ELSE 0 END)
        FROM OrderItem i
        WHERE i.product IS NOT NULL
          AND i.order.createdAt >= :from
          AND i.order.createdAt <  :to
        GROUP BY i.product.id, i.product.name, i.product.sku
        ORDER BY SUM(CASE WHEN i.order.status = 'LIVRE' THEN i.quantity ELSE 0 END) DESC
        """)
    List<Object[]> aggregateByProductAndDateRange(
            @Param("from") LocalDateTime from,
            @Param("to")   LocalDateTime to);
}
