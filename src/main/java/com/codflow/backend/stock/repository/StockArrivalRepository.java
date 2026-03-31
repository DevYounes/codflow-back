package com.codflow.backend.stock.repository;

import com.codflow.backend.stock.entity.StockArrival;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockArrivalRepository extends JpaRepository<StockArrival, Long> {
    Optional<StockArrival> findByReference(String reference);
    boolean existsByReference(String reference);
    List<StockArrival> findByProductIdOrderByArrivedAtDesc(Long productId);
}
