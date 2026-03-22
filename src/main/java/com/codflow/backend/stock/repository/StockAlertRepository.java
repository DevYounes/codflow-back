package com.codflow.backend.stock.repository;

import com.codflow.backend.stock.entity.StockAlert;
import com.codflow.backend.stock.enums.AlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {

    List<StockAlert> findByResolvedFalse();

    Page<StockAlert> findByResolved(boolean resolved, Pageable pageable);

    boolean existsByProductIdAndAlertTypeAndResolvedFalse(Long productId, AlertType alertType);

    List<StockAlert> findByProductIdAndResolvedFalse(Long productId);
}
