package com.codflow.backend.order.repository;

import com.codflow.backend.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrderId(Long orderId);

    boolean existsByVariantId(Long variantId);

    /** Tous les items non encore liés à un produit (à relier via le backfill). */
    @Query("SELECT i FROM OrderItem i WHERE i.product IS NULL")
    List<OrderItem> findByProductIsNull();
}
