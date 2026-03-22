package com.codflow.backend.order.repository;

import com.codflow.backend.order.entity.Order;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    boolean existsByShopifyOrderId(String shopifyOrderId);

    @Query("SELECT o FROM Order o WHERE o.assignedTo.id = :agentId AND o.status NOT IN " +
           "('CONFIRME', 'ANNULE', 'PAS_SERIEUX', 'FAKE_ORDER', 'DOUBLON', 'LIVRE', 'RETOURNE')")
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

    @Query("SELECT o.assignedTo.id, COUNT(o), SUM(CASE WHEN o.status = 'CONFIRME' THEN 1 ELSE 0 END) " +
           "FROM Order o WHERE o.assignedTo IS NOT NULL GROUP BY o.assignedTo.id")
    List<Object[]> getAgentPerformanceStats();

    @Query("SELECT DATE(o.createdAt), COUNT(o), SUM(CASE WHEN o.status = 'CONFIRME' THEN 1 ELSE 0 END) " +
           "FROM Order o WHERE o.createdAt BETWEEN :from AND :to GROUP BY DATE(o.createdAt) ORDER BY DATE(o.createdAt)")
    List<Object[]> getDailyStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT o FROM Order o WHERE o.assignedTo IS NULL AND o.status = 'NOUVEAU'")
    List<Order> findUnassignedNewOrders();
}
