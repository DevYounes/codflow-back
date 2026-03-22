package com.codflow.backend.order.repository;

import com.codflow.backend.order.entity.Order;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.enums.OrderStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class OrderSpecification {

    public static Specification<Order> withFilters(OrderStatus status, Collection<OrderStatus> statuses,
                                                   OrderSource source,
                                                   Long assignedTo, String search,
                                                   LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            } else if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (source != null) {
                predicates.add(cb.equal(root.get("source"), source));
            }
            if (assignedTo != null) {
                predicates.add(cb.equal(root.get("assignedTo").get("id"), assignedTo));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("customerName")), pattern),
                        cb.like(cb.lower(root.get("customerPhone")), pattern),
                        cb.like(cb.lower(root.get("orderNumber")), pattern)
                ));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
