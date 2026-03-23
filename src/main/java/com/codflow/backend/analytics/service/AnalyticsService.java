package com.codflow.backend.analytics.service;

import com.codflow.backend.analytics.dto.*;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.enums.OrderStatus;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.security.UserPrincipal;
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
        long totalDeliveryAttempted = deliveredOrders + returnedOrders + inDeliveryOrders;
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
                .totalRevenue(calculateRevenue(OrderStatus.LIVRE))
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

        // Taux basé sur les commandes expédiées (évite division par 0 si table shipments vide)
        long ordersDelivered = orderRepository.countByStatus(OrderStatus.LIVRE);
        long ordersReturned  = orderRepository.countByStatus(OrderStatus.RETOURNE);
        long ordersInTransit = orderRepository.countByStatus(OrderStatus.EN_LIVRAISON) +
                               orderRepository.countByStatus(OrderStatus.ENVOYE) +
                               orderRepository.countByStatus(OrderStatus.EN_PREPARATION);
        long ordersExpedited = ordersDelivered + ordersReturned + ordersInTransit;

        double deliveryRate = ordersExpedited > 0 ? round((double) ordersDelivered / ordersExpedited * 100) : 0;
        double returnRate   = ordersExpedited > 0 ? round((double) ordersReturned  / ordersExpedited * 100) : 0;

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

    private static final List<OrderStatus> CANCELLED_STATUSES = List.of(
            OrderStatus.ANNULE, OrderStatus.PAS_SERIEUX,
            OrderStatus.FAKE_ORDER, OrderStatus.DOUBLON);

    @Transactional(readOnly = true)
    public AgentDashboardDto getAgentDashboard(UserPrincipal principal) {
        User agent = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Agent", principal.getId()));

        Long agentId = agent.getId();
        LocalDateTime now        = LocalDateTime.now();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart  = LocalDate.now().minusDays(6).atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        long total     = orderRepository.countByAgentAndDateRange(agentId, LocalDateTime.of(2000,1,1,0,0), now);
        long today     = orderRepository.countByAgentAndDateRange(agentId, todayStart, now);
        long week      = orderRepository.countByAgentAndDateRange(agentId, weekStart, now);
        long month     = orderRepository.countByAgentAndDateRange(agentId, monthStart, now);
        long confirmed = orderRepository.countByAgentAndStatus(agentId, OrderStatus.CONFIRME);
        long cancelled = orderRepository.countByAgentAndStatuses(agentId, CANCELLED_STATUSES);
        long pending   = Math.max(0, total - confirmed - cancelled);
        long duplicates = orderRepository.countPotentialDuplicatesByAgent(agentId);

        double confirmRate = total > 0 ? round((double) confirmed / total * 100) : 0;
        double cancelRate  = total > 0 ? round((double) cancelled / total * 100) : 0;

        // Daily trend (last 7 days, agent's orders only)
        List<Object[]> raw = orderRepository.getDailyStatsForAgent(agentId, weekStart, now);
        List<DailyStatsDto> trend = raw.stream().map(row -> {
            long dayTotal     = ((Number) row[1]).longValue();
            long dayConfirmed = ((Number) row[2]).longValue();
            return DailyStatsDto.builder()
                    .date(row[0].toString())
                    .totalOrders(dayTotal)
                    .confirmedOrders(dayConfirmed)
                    .confirmationRate(dayTotal > 0 ? round((double) dayConfirmed / dayTotal * 100) : 0)
                    .revenue(BigDecimal.ZERO)
                    .build();
        }).collect(Collectors.toList());

        return AgentDashboardDto.builder()
                .agentId(agentId)
                .agentName(agent.getFullName())
                .totalAssigned(total)
                .todayOrders(today)
                .weekOrders(week)
                .monthOrders(month)
                .confirmedOrders(confirmed)
                .cancelledOrders(cancelled)
                .pendingOrders(pending)
                .potentialDuplicates(duplicates)
                .confirmationRate(confirmRate)
                .cancellationRate(cancelRate)
                .dailyTrend(trend)
                .build();
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
        return orderRepository.countByAgentAndStatuses(agentId, CANCELLED_STATUSES);
    }

    private BigDecimal calculateRevenue(OrderStatus status) {
        if (status == null) {
            return orderRepository.sumTotalRevenue();
        }
        return orderRepository.sumRevenueByStatus(status);
    }

    private BigDecimal calculateAverageOrderValue() {
        return orderRepository.avgOrderValue();
    }

    private Map<String, Long> getOrdersBySource() {
        return orderRepository.countGroupBySource()
                .stream()
                .collect(Collectors.toMap(
                        row -> ((OrderSource) row[0]).name(),
                        row -> (Long) row[1]
                ));
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
