package com.codflow.backend.order.repository;

import com.codflow.backend.order.entity.Order;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    /**
     * Vérifie l'existence d'un order_number en bypassant le @SQLRestriction("deleted = false").
     * À utiliser pour la détection de collisions au niveau de la contrainte unique DB :
     * une commande soft-deleted occupe toujours son order_number en base.
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM orders WHERE order_number = :orderNumber)",
            nativeQuery = true)
    boolean existsByOrderNumberIncludingDeleted(@Param("orderNumber") String orderNumber);

    boolean existsByShopifyOrderId(String shopifyOrderId);

    Optional<Order> findByShopifyOrderId(String shopifyOrderId);

    Optional<Order> findByExternalRef(String externalRef);

    boolean existsByExternalRef(String externalRef);

    @Query("SELECT o FROM Order o WHERE o.assignedTo.id = :agentId AND o.status NOT IN " +
           "('CONFIRME', 'ANNULE', 'PAS_SERIEUX', 'FAKE_ORDER', 'DOUBLON', 'CLIENT_BLACKLISTE', 'LIVRE', 'RETOURNE')")
    List<Order> findActiveOrdersByAgent(@Param("agentId") Long agentId);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status")
    long countByStatus(@Param("status") OrderStatus status);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status AND o.createdAt BETWEEN :from AND :to")
    long countByStatusAndDateRange(@Param("status") OrderStatus status,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.createdAt BETWEEN :from AND :to")
    long countByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT o.status, COUNT(o) FROM Order o GROUP BY o.status")
    List<Object[]> countGroupByStatus();

    @Query("SELECT o.assignedTo.id, COUNT(o), " +
           "SUM(CASE WHEN o.status IN ('CONFIRME','EN_PREPARATION','ENVOYE','EN_LIVRAISON','LIVRE','ECHEC_LIVRAISON','RETOURNE') THEN 1 ELSE 0 END) " +
           "FROM Order o WHERE o.assignedTo IS NOT NULL GROUP BY o.assignedTo.id")
    List<Object[]> getAgentPerformanceStats();

    @Query("SELECT DATE(o.createdAt), COUNT(o), " +
           "SUM(CASE WHEN o.status IN ('CONFIRME','EN_PREPARATION','ENVOYE','EN_LIVRAISON','LIVRE','ECHEC_LIVRAISON','RETOURNE') THEN 1 ELSE 0 END) " +
           "FROM Order o WHERE o.createdAt BETWEEN :from AND :to GROUP BY DATE(o.createdAt) ORDER BY DATE(o.createdAt)")
    List<Object[]> getDailyStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT o FROM Order o WHERE o.assignedTo IS NULL AND o.status = 'NOUVEAU'")
    List<Order> findUnassignedNewOrders();

    boolean existsByCustomerPhoneNormalized(String customerPhoneNormalized);

    /**
     * Toutes les commandes d'un client (non soft-deleted grâce au @SQLRestriction),
     * tous agents confondus. Utilisé pour permettre à un agent de consulter l'historique
     * complet d'un client (incl. commandes d'autres agents).
     */
    Page<Order> findByCustomerId(Long customerId, Pageable pageable);

    // ---- Agent-specific stats ----

    @Query("SELECT COUNT(o) FROM Order o WHERE o.assignedTo.id = :agentId AND o.status = :status")
    long countByAgentAndStatus(@Param("agentId") Long agentId, @Param("status") OrderStatus status);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.assignedTo.id = :agentId AND o.status IN :statuses")
    long countByAgentAndStatuses(@Param("agentId") Long agentId, @Param("statuses") Collection<OrderStatus> statuses);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.assignedTo.id = :agentId AND o.createdAt BETWEEN :from AND :to")
    long countByAgentAndDateRange(@Param("agentId") Long agentId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    // ---- Salary commission counters ----

    /** Commandes confirmées par un agent dont la confirmation a eu lieu dans la période (commissions « par confirmé »). */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.assignedTo.id = :agentId " +
           "AND o.confirmedAt IS NOT NULL AND o.confirmedAt BETWEEN :from AND :to")
    long countConfirmedByAgentInPeriod(@Param("agentId") Long agentId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.assignedTo.id = :agentId AND o.potentialDuplicate = true")
    long countPotentialDuplicatesByAgent(@Param("agentId") Long agentId);

    @Query("SELECT DATE(o.createdAt), COUNT(o), " +
           "SUM(CASE WHEN o.status IN ('CONFIRME','EN_PREPARATION','ENVOYE','EN_LIVRAISON','LIVRE','ECHEC_LIVRAISON','RETOURNE') THEN 1 ELSE 0 END) " +
           "FROM Order o WHERE o.assignedTo.id = :agentId AND o.createdAt BETWEEN :from AND :to " +
           "GROUP BY DATE(o.createdAt) ORDER BY DATE(o.createdAt)")
    List<Object[]> getDailyStatsForAgent(@Param("agentId") Long agentId,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status IN :statuses")
    long countByStatuses(@Param("statuses") Collection<OrderStatus> statuses);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status IN :statuses AND o.createdAt BETWEEN :from AND :to")
    long countByStatusesAndDateRange(@Param("statuses") Collection<OrderStatus> statuses,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    @Query("SELECT o.status, COUNT(o) FROM Order o WHERE o.createdAt BETWEEN :from AND :to GROUP BY o.status")
    List<Object[]> countGroupByStatusAndDateRange(@Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to);

    @Query("SELECT o.source, COUNT(o) FROM Order o WHERE o.source IS NOT NULL AND o.createdAt BETWEEN :from AND :to GROUP BY o.source")
    List<Object[]> countGroupBySourceAndDateRange(@Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.status = :status AND o.createdAt BETWEEN :from AND :to")
    BigDecimal sumRevenueByStatusAndDateRange(@Param("status") OrderStatus status,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(AVG(o.totalAmount), 0) FROM Order o WHERE o.totalAmount IS NOT NULL AND o.createdAt BETWEEN :from AND :to")
    BigDecimal avgOrderValueByDateRange(@Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.assignedTo.id = :agentId AND o.status = :status")
    BigDecimal sumRevenueByAgentAndStatus(@Param("agentId") Long agentId, @Param("status") OrderStatus status);

    // ---- Revenue ----

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o")
    BigDecimal sumTotalRevenue();

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.status = :status")
    BigDecimal sumRevenueByStatus(@Param("status") OrderStatus status);

    @Query("SELECT COALESCE(AVG(o.totalAmount), 0) FROM Order o WHERE o.totalAmount IS NOT NULL")
    BigDecimal avgOrderValue();

    @Query("SELECT o.source, COUNT(o) FROM Order o WHERE o.source IS NOT NULL GROUP BY o.source")
    List<Object[]> countGroupBySource();

    // ---- Customer hard-delete support (bypass @SQLRestriction) ----

    @Query(value = "SELECT id FROM orders WHERE customer_id = :customerId", nativeQuery = true)
    List<Long> findAllOrderIdsByCustomerId(@Param("customerId") Long customerId);

    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items i LEFT JOIN FETCH i.product LEFT JOIN FETCH i.variant WHERE o.id IN :ids AND o.stockReserved = true")
    List<Order> findReservedOrdersWithItemsByIds(@Param("ids") List<Long> ids);

    @Modifying
    @Query(value = "UPDATE orders SET source_order_id = NULL WHERE source_order_id IN :orderIds", nativeQuery = true)
    void clearSourceOrderReferences(@Param("orderIds") List<Long> orderIds);

    @Modifying
    @Query(value = "DELETE FROM order_status_history WHERE order_id IN :orderIds", nativeQuery = true)
    void deleteStatusHistoryByOrderIds(@Param("orderIds") List<Long> orderIds);

    @Modifying
    @Query(value = "DELETE FROM order_items WHERE order_id IN :orderIds", nativeQuery = true)
    void deleteItemsByOrderIds(@Param("orderIds") List<Long> orderIds);

    @Modifying
    @Query(value = "DELETE FROM orders WHERE customer_id = :customerId", nativeQuery = true)
    void hardDeleteAllByCustomerId(@Param("customerId") Long customerId);
}
