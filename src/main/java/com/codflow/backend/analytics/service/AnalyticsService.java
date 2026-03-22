package com.codflow.backend.analytics.service;

import com.codflow.backend.analytics.dto.*;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import com.codflow.backend.order.enums.OrderStatus;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.stock.repository.StockAlertRepository;
import com.codflow.backend.team.entity.User;
import com.codflow.backend.team.enums.Role;
import com.codflow.backend.team.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final StockAlertRepository stockAlertRepository;
    private final DeliveryShipmentRepository shipmentRepository;

    @Transactional(readOnly = true)
    public KpiSummaryDto getSummary(LocalDateTime from, LocalDateTime to) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        // Counts
        long totalOrders = orderRepository.count();
        long todayOrders = orderRepository.countByDateRange(todayStart, now);
        long weekOrders = orderRepository.countByDateRange(weekStart, now);
        long monthOrders = orderRepository.countByDateRange(monthStart, now);

        long confirmedOrders = orderRepository.countByStatus(OrderStatus.CONFIRME);
        long cancelledOrders = countCancelled();
        long pendingOrders = countPending();
        long deliveredOrders = orderRepository.countByStatus(OrderStatus.LIVRE);
        long inDeliveryOrders = orderRepository.countByStatus(OrderStatus.EN_LIVRAISON) +
                                orderRepository.countByStatus(OrderStatus.ENVOYE) +
                                orderRepository.countByStatus(OrderStatus.EN_PREPARATION);
        long returnedOrders = orderRepository.countByStatus(OrderStatus.RETOURNE);

        // Rates
        double confirmationRate = totalOrders > 0 ? round((double) confirmedOrders / totalOrders * 100) : 0;
        double cancellationRate = totalOrders > 0 ? round((double) cancelledOrders / totalOrders * 100) : 0;
        long totalDeliveryAttempted = deliveredOrders + returnedOrders;
        double deliverySuccessRate = totalDeliveryAttempted > 0 ? round((double) deliveredOrders / totalDeliveryAttempted * 100) : 0;
        double returnRate = totalDeliveryAttempted > 0 ? round((double) returnedOrders / totalDeliveryAttempted * 100) : 0;

        // Order by status breakdown
        Map<String, Long> ordersByStatus = orderRepository.countGroupByStatus()
                .stream().collect(Collectors.toMap(
                        row -> ((OrderStatus) row[0]).getLabel(),
                        row -> (Long) row[1]
                ));

        // Stock
        long lowStockProducts = productRepository.findLowStockProducts().size();
        long outOfStockProducts = productRepository.findOutOfStockProducts().size();
        long activeAlerts = stockAlertRepository.findByResolvedFalse().size();

        // Daily trend (last 7 days)
        List<DailyStatsDto> dailyTrend = getDailyTrend(7);

        return KpiSummaryDto.builder()
                .totalOrders(totalOrders)
                .todayOrders(todayOrders)
                .weekOrders(weekOrders)
                .monthOrders(monthOrders)
                .confirmedOrders(confirmedOrders)
                .cancelledOrders(cancelledOrders)
                .pendingOrders(pendingOrders)
                .confirmationRate(confirmationRate)
                .cancellationRate(cancellationRate)
                .deliveredOrders(deliveredOrders)
                .inDeliveryOrders(inDeliveryOrders)
                .returnedOrders(returnedOrders)
                .deliverySuccessRate(deliverySuccessRate)
                .returnRate(returnRate)
                .totalRevenue(calculateRevenue(null))
                .confirmedRevenue(calculateRevenue(OrderStatus.CONFIRME))
                .averageOrderValue(calculateAverageOrderValue())
                .lowStockProducts(lowStockProducts)
                .outOfStockProducts(outOfStockProducts)
                .activeAlerts(activeAlerts)
                .ordersByStatus(ordersByStatus)
                .ordersBySource(getOrdersBySource())
                .dailyTrend(dailyTrend)
                .build();
    }

    @Transactional(readOnly = true)
    public List<AgentPerformanceDto> getAgentPerformance() {
        List<User> agents = userRepository.findByRoleAndActiveTrue(Role.AGENT);
        List<Object[]> stats = orderRepository.getAgentPerformanceStats();

        Map<Long, long[]> statsMap = new HashMap<>();
        stats.forEach(row -> {
            Long agentId = (Long) row[0];
            long total = (Long) row[1];
            long confirmed = (Long) row[2];
            statsMap.put(agentId, new long[]{total, confirmed});
        });

        return agents.stream().map(agent -> {
            long[] agentStats = statsMap.getOrDefault(agent.getId(), new long[]{0, 0});
            long total = agentStats[0];
            long confirmed = agentStats[1];
            long cancelled = countCancelledForAgent(agent.getId());
            long pending = total - confirmed - cancelled;
            double confirmRate = total > 0 ? round((double) confirmed / total * 100) : 0;
            double cancelRate = total > 0 ? round((double) cancelled / total * 100) : 0;

            return AgentPerformanceDto.builder()
                    .agentId(agent.getId())
                    .agentName(agent.getFullName())
                    .agentUsername(agent.getUsername())
                    .totalAssigned(total)
                    .confirmed(confirmed)
                    .cancelled(cancelled)
                    .pending(Math.max(0, pending))
                    .confirmationRate(confirmRate)
                    .cancellationRate(cancelRate)
                    .build();
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DeliveryStatsDto getDeliveryStats() {
        long totalShipments = shipmentRepository.count();
        long delivered = shipmentRepository.countByStatus(ShipmentStatus.DELIVERED);
        long inTransit = shipmentRepository.countByStatus(ShipmentStatus.IN_TRANSIT) +
                         shipmentRepository.countByStatus(ShipmentStatus.OUT_FOR_DELIVERY);
        long returned = shipmentRepository.countByStatus(ShipmentStatus.RETURNED);
        long failed = shipmentRepository.countByStatus(ShipmentStatus.FAILED_DELIVERY);

        double deliveryRate = totalShipments > 0 ? round((double) delivered / totalShipments * 100) : 0;
        double returnRate = totalShipments > 0 ? round((double) returned / totalShipments * 100) : 0;

        return DeliveryStatsDto.builder()
                .totalShipments(totalShipments)
                .delivered(delivered)
                .inTransit(inTransit)
                .returned(returned)
                .failed(failed)
                .deliveryRate(deliveryRate)
                .returnRate(returnRate)
                .shipmentsByStatus(getShipmentsByStatus())
                .build();
    }

    @Transactional(readOnly = true)
    public List<DailyStatsDto> getDailyTrend(int days) {
        LocalDateTime from = LocalDate.now().minusDays(days - 1).atStartOfDay();
        LocalDateTime to = LocalDateTime.now();

        List<Object[]> rawStats = orderRepository.getDailyStats(from, to);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        return rawStats.stream().map(row -> {
            String date = row[0].toString();
            long total = ((Number) row[1]).longValue();
            long confirmed = ((Number) row[2]).longValue();
            double rate = total > 0 ? round((double) confirmed / total * 100) : 0;

            return DailyStatsDto.builder()
                    .date(date)
                    .totalOrders(total)
                    .confirmedOrders(confirmed)
                    .confirmationRate(rate)
                    .revenue(BigDecimal.ZERO) // TODO: add revenue calculation
                    .build();
        }).collect(Collectors.toList());
    }

    private long countCancelled() {
        return orderRepository.countByStatus(OrderStatus.ANNULE) +
               orderRepository.countByStatus(OrderStatus.PAS_SERIEUX) +
               orderRepository.countByStatus(OrderStatus.FAKE_ORDER) +
               orderRepository.countByStatus(OrderStatus.DOUBLON);
    }

    private long countPending() {
        return orderRepository.countByStatus(OrderStatus.NOUVEAU) +
               orderRepository.countByStatus(OrderStatus.APPEL_1) +
               orderRepository.countByStatus(OrderStatus.APPEL_2) +
               orderRepository.countByStatus(OrderStatus.APPEL_3) +
               orderRepository.countByStatus(OrderStatus.MESSAGE_WHATSAPP) +
               orderRepository.countByStatus(OrderStatus.PAS_DE_REPONSE) +
               orderRepository.countByStatus(OrderStatus.INJOIGNABLE) +
               orderRepository.countByStatus(OrderStatus.REPORTE);
    }

    private long countCancelledForAgent(Long agentId) {
        // Simplified - would need a more specific query for production
        return 0;
    }

    private BigDecimal calculateRevenue(OrderStatus status) {
        // Simplified - would use native query for performance
        return BigDecimal.ZERO;
    }

    private BigDecimal calculateAverageOrderValue() {
        return BigDecimal.ZERO;
    }

    private Map<String, Long> getOrdersBySource() {
        // Simplified
        return new HashMap<>();
    }

    private Map<String, Long> getShipmentsByStatus() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (ShipmentStatus status : ShipmentStatus.values()) {
            long count = shipmentRepository.countByStatus(status);
            if (count > 0) map.put(status.getLabel(), count);
        }
        return map;
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
