package com.codflow.backend.salary.repository;

import com.codflow.backend.salary.entity.SalaryPayment;
import com.codflow.backend.salary.enums.SalaryPaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface SalaryPaymentRepository extends JpaRepository<SalaryPayment, Long>,
        JpaSpecificationExecutor<SalaryPayment> {

    List<SalaryPayment> findByUserIdOrderByPeriodStartDesc(Long userId);

    Page<SalaryPayment> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(sp.totalAmount), 0) FROM SalaryPayment sp " +
           "WHERE sp.status = :status AND sp.paymentDate BETWEEN :from AND :to")
    BigDecimal sumPaidBetween(@Param("status") SalaryPaymentStatus status,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to);

    @Query("SELECT COUNT(sp) > 0 FROM SalaryPayment sp " +
           "WHERE sp.user.id = :userId AND sp.periodStart = :periodStart AND sp.periodEnd = :periodEnd " +
           "AND sp.status <> 'ANNULE'")
    boolean existsActiveForUserAndPeriod(@Param("userId") Long userId,
                                         @Param("periodStart") LocalDate periodStart,
                                         @Param("periodEnd") LocalDate periodEnd);
}
