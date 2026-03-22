package com.codflow.backend.stock.repository;

import com.codflow.backend.stock.entity.StockMovement;
import com.codflow.backend.stock.enums.MovementType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    Page<StockMovement> findByProductId(Long productId, Pageable pageable);

    List<StockMovement> findByProductIdAndMovementType(Long productId, MovementType type);

    @Query("SELECT sm FROM StockMovement sm WHERE sm.createdAt BETWEEN :from AND :to ORDER BY sm.createdAt DESC")
    List<StockMovement> findByDateRange(LocalDateTime from, LocalDateTime to);
}
