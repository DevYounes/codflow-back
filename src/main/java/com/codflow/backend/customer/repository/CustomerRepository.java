package com.codflow.backend.customer.repository;

import com.codflow.backend.customer.entity.Customer;
import com.codflow.backend.customer.enums.CustomerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByPhoneNormalized(String phoneNormalized);

    Optional<Customer> findByPhone(String phone);

    boolean existsByPhoneNormalized(String phoneNormalized);

    @Query("""
            SELECT c FROM Customer c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:searchPattern IS NULL
                   OR LOWER(c.fullName) LIKE :searchPattern
                   OR c.phone LIKE :searchPattern
                   OR LOWER(c.ville) LIKE :searchPattern)
            """)
    Page<Customer> findWithFilters(
            @Param("status") CustomerStatus status,
            @Param("searchPattern") String searchPattern,
            Pageable pageable);

    // ---- Tri par totalOrders (champ calculé — native SQL) ----

    @Query(value = """
            SELECT c.* FROM customers c
            LEFT JOIN (
                SELECT customer_id, COUNT(*) AS cnt
                FROM orders WHERE deleted = false
                GROUP BY customer_id
            ) s ON c.id = s.customer_id
            WHERE (:status IS NULL OR c.status = :status)
              AND (:search IS NULL
                   OR LOWER(c.full_name) LIKE :search
                   OR c.phone LIKE :search
                   OR LOWER(c.ville) LIKE :search)
            ORDER BY COALESCE(s.cnt, 0) DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM customers c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:search IS NULL OR LOWER(c.full_name) LIKE :search
                   OR c.phone LIKE :search OR LOWER(c.ville) LIKE :search)
            """,
            nativeQuery = true)
    Page<Customer> findSortedByTotalOrdersDesc(@Param("status") String status,
                                               @Param("search") String search,
                                               Pageable pageable);

    @Query(value = """
            SELECT c.* FROM customers c
            LEFT JOIN (
                SELECT customer_id, COUNT(*) AS cnt
                FROM orders WHERE deleted = false
                GROUP BY customer_id
            ) s ON c.id = s.customer_id
            WHERE (:status IS NULL OR c.status = :status)
              AND (:search IS NULL
                   OR LOWER(c.full_name) LIKE :search
                   OR c.phone LIKE :search
                   OR LOWER(c.ville) LIKE :search)
            ORDER BY COALESCE(s.cnt, 0) ASC
            """,
            countQuery = """
            SELECT COUNT(*) FROM customers c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:search IS NULL OR LOWER(c.full_name) LIKE :search
                   OR c.phone LIKE :search OR LOWER(c.ville) LIKE :search)
            """,
            nativeQuery = true)
    Page<Customer> findSortedByTotalOrdersAsc(@Param("status") String status,
                                              @Param("search") String search,
                                              Pageable pageable);

    // ---- Tri par taux de confirmation (champ calculé — native SQL) ----

    @Query(value = """
            SELECT c.* FROM customers c
            LEFT JOIN (
                SELECT customer_id,
                       COUNT(*) AS total,
                       SUM(CASE WHEN status IN
                           ('CONFIRME','EN_PREPARATION','ENVOYE','EN_LIVRAISON',
                            'LIVRE','ECHEC_LIVRAISON','RETOURNE')
                           THEN 1 ELSE 0 END) AS confirmed
                FROM orders WHERE deleted = false
                GROUP BY customer_id
            ) s ON c.id = s.customer_id
            WHERE (:status IS NULL OR c.status = :status)
              AND (:search IS NULL
                   OR LOWER(c.full_name) LIKE :search
                   OR c.phone LIKE :search
                   OR LOWER(c.ville) LIKE :search)
            ORDER BY CASE WHEN COALESCE(s.total, 0) > 0
                          THEN (s.confirmed * 100.0 / s.total) ELSE 0 END DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM customers c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:search IS NULL OR LOWER(c.full_name) LIKE :search
                   OR c.phone LIKE :search OR LOWER(c.ville) LIKE :search)
            """,
            nativeQuery = true)
    Page<Customer> findSortedByConfirmationRateDesc(@Param("status") String status,
                                                    @Param("search") String search,
                                                    Pageable pageable);

    @Query(value = """
            SELECT c.* FROM customers c
            LEFT JOIN (
                SELECT customer_id,
                       COUNT(*) AS total,
                       SUM(CASE WHEN status IN
                           ('CONFIRME','EN_PREPARATION','ENVOYE','EN_LIVRAISON',
                            'LIVRE','ECHEC_LIVRAISON','RETOURNE')
                           THEN 1 ELSE 0 END) AS confirmed
                FROM orders WHERE deleted = false
                GROUP BY customer_id
            ) s ON c.id = s.customer_id
            WHERE (:status IS NULL OR c.status = :status)
              AND (:search IS NULL
                   OR LOWER(c.full_name) LIKE :search
                   OR c.phone LIKE :search
                   OR LOWER(c.ville) LIKE :search)
            ORDER BY CASE WHEN COALESCE(s.total, 0) > 0
                          THEN (s.confirmed * 100.0 / s.total) ELSE 0 END ASC
            """,
            countQuery = """
            SELECT COUNT(*) FROM customers c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:search IS NULL OR LOWER(c.full_name) LIKE :search
                   OR c.phone LIKE :search OR LOWER(c.ville) LIKE :search)
            """,
            nativeQuery = true)
    Page<Customer> findSortedByConfirmationRateAsc(@Param("status") String status,
                                                   @Param("search") String search,
                                                   Pageable pageable);

    // ---- Stats par client ----

    @Query("SELECT COUNT(o) FROM Order o WHERE o.customer.id = :customerId")
    long countOrdersByCustomer(@Param("customerId") Long customerId);

    @Query("""
            SELECT COUNT(o) FROM Order o WHERE o.customer.id = :customerId
            AND o.status IN ('CONFIRME','EN_PREPARATION','ENVOYE','EN_LIVRAISON','LIVRE','ECHEC_LIVRAISON','RETOURNE')
            """)
    long countConfirmedOrdersByCustomer(@Param("customerId") Long customerId);

    @Query("""
            SELECT COUNT(o) FROM Order o WHERE o.customer.id = :customerId
            AND o.status IN ('ANNULE','PAS_SERIEUX','FAKE_ORDER','CLIENT_BLACKLISTE')
            """)
    long countCancelledOrdersByCustomer(@Param("customerId") Long customerId);

    @Query("SELECT MAX(o.createdAt) FROM Order o WHERE o.customer.id = :customerId")
    java.time.LocalDateTime lastOrderDateByCustomer(@Param("customerId") Long customerId);
}

