package com.codflow.backend.analytics.service;

import com.codflow.backend.analytics.dto.*;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.enums.OrderStatus;
import com.codflow.backend.order.repository.OrderItemRepository;
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
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final StockAlertRepository stockAlertRepository;
    private final DeliveryShipmentRepository shipmentRepository;

    @Transactional(readOnly = true)
    public KpiSummaryDto getSummary(LocalDateTime from, LocalDateTime to) {
        LocalDateTime now        = LocalDateTime.now();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart  = LocalDate.now().minusDays(7).atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        boolean filtered = from != null || to != null;
        // Normalise les bornes : si l'une est absente on utilise une borne ouverte cohérente
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime effectiveTo   = to   != null ? to   : now;

        // Counts — filtered by date range if provided
        long totalOrders = filtered
                ? orderRepository.countByDateRange(effectiveFrom, effectiveTo)
                : orderRepository.count();

        // todayOrders / weekOrders / monthOrders = toujours relatifs à aujourd'hui
        long todayOrders  = orderRepository.countByDateRange(todayStart, now);
        long weekOrders   = orderRepository.countByDateRange(weekStart, now);
        long monthOrders  = orderRepository.countByDateRange(monthStart, now);

        long confirmedOrders = filtered
                ? orderRepository.countByStatusesAndDateRange(CONFIRMED_PIPELINE_STATUSES, effectiveFrom, effectiveTo)
                : orderRepository.countByStatuses(CONFIRMED_PIPELINE_STATUSES);
        long cancelledOrders = filtered ? countCancelledFiltered(effectiveFrom, effectiveTo) : countCancelled();
        long doublonOrders   = filtered
                ? orderRepository.countByStatusAndDateRange(OrderStatus.DOUBLON, effectiveFrom, effectiveTo)
                : orderRepository.countByStatus(OrderStatus.DOUBLON);
        long pendingOrders   = filtered ? countPendingFiltered(effectiveFrom, effectiveTo) : countPending();
        long deliveredOrders = filtered
                ? orderRepository.countByStatusAndDateRange(OrderStatus.LIVRE, effectiveFrom, effectiveTo)
                : orderRepository.countByStatus(OrderStatus.LIVRE);
        long inDeliveryOrders = filtered
                ? orderRepository.countByStatusAndDateRange(OrderStatus.EN_LIVRAISON, effectiveFrom, effectiveTo)
                  + orderRepository.countByStatusAndDateRange(OrderStatus.ENVOYE, effectiveFrom, effectiveTo)
                  + orderRepository.countByStatusAndDateRange(OrderStatus.EN_PREPARATION, effectiveFrom, effectiveTo)
                : orderRepository.countByStatus(OrderStatus.EN_LIVRAISON)
                  + orderRepository.countByStatus(OrderStatus.ENVOYE)
                  + orderRepository.countByStatus(OrderStatus.EN_PREPARATION);
        long returnedOrders = filtered
                ? orderRepository.countByStatusAndDateRange(OrderStatus.RETOURNE, effectiveFrom, effectiveTo)
                : orderRepository.countByStatus(OrderStatus.RETOURNE);

        // Rates
        double confirmationRate = totalOrders > 0 ? round((double) confirmedOrders / totalOrders * 100) : 0;
        double cancellationRate = totalOrders > 0 ? round((double) cancelledOrders / totalOrders * 100) : 0;
        long totalDeliveryAttempted = deliveredOrders + returnedOrders + inDeliveryOrders;
        double deliverySuccessRate = totalDeliveryAttempted > 0 ? round((double) deliveredOrders / totalDeliveryAttempted * 100) : 0;
        double returnRate = totalDeliveryAttempted > 0 ? round((double) returnedOrders / totalDeliveryAttempted * 100) : 0;

        // Status breakdown
        Map<String, Long> ordersByStatus = (filtered
                ? orderRepository.countGroupByStatusAndDateRange(effectiveFrom, effectiveTo)
                : orderRepository.countGroupByStatus())
                .stream().collect(Collectors.toMap(
                        row -> ((OrderStatus) row[0]).getLabel(),
                        row -> (Long) row[1]
                ));

        // Source breakdown
        Map<String, Long> ordersBySource = (filtered
                ? orderRepository.countGroupBySourceAndDateRange(effectiveFrom, effectiveTo)
                : orderRepository.countGroupBySource())
                .stream().collect(Collectors.toMap(
                        row -> ((OrderSource) row[0]).name(),
                        row -> (Long) row[1]
                ));

        // Revenue
        BigDecimal totalRevenue = filtered
                ? orderRepository.sumRevenueByStatusAndDateRange(OrderStatus.LIVRE, effectiveFrom, effectiveTo)
                : calculateRevenue(OrderStatus.LIVRE);
        BigDecimal confirmedRevenue = filtered
                ? orderRepository.sumRevenueByStatusAndDateRange(OrderStatus.CONFIRME, effectiveFrom, effectiveTo)
                : calculateRevenue(OrderStatus.CONFIRME);
        BigDecimal avgOrderValue = filtered
                ? orderRepository.avgOrderValueByDateRange(effectiveFrom, effectiveTo)
                : calculateAverageOrderValue();

        // Stock (pas de filtre par date — c'est l'état actuel du stock)
        long lowStockProducts   = productRepository.findLowStockProducts().size();
        long outOfStockProducts = productRepository.findOutOfStockProducts().size();
        long activeAlerts       = stockAlertRepository.findByResolvedFalse().size();

        // Daily trend — sur la plage filtrée ou les 7 derniers jours
        List<DailyStatsDto> dailyTrend = filtered
                ? getDailyTrendBetween(effectiveFrom, effectiveTo)
                : getDailyTrend(7);

        return KpiSummaryDto.builder()
                .totalOrders(totalOrders)
                .todayOrders(todayOrders)
                .weekOrders(weekOrders)
                .monthOrders(monthOrders)
                .confirmedOrders(confirmedOrders)
                .cancelledOrders(cancelledOrders)
                .doublonOrders(doublonOrders)
                .pendingOrders(pendingOrders)
                .confirmationRate(confirmationRate)
                .cancellationRate(cancellationRate)
                .deliveredOrders(deliveredOrders)
                .inDeliveryOrders(inDeliveryOrders)
                .returnedOrders(returnedOrders)
                .deliverySuccessRate(deliverySuccessRate)
                .returnRate(returnRate)
                .totalRevenue(totalRevenue)
                .confirmedRevenue(confirmedRevenue)
                .averageOrderValue(avgOrderValue)
                .lowStockProducts(lowStockProducts)
                .outOfStockProducts(outOfStockProducts)
                .activeAlerts(activeAlerts)
                .ordersByStatus(ordersByStatus)
                .ordersBySource(ordersBySource)
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
            long total = ((Number) row[1]).longValue();
            long confirmed = ((Number) row[2]).longValue();
            statsMap.put(agentId, new long[]{total, confirmed});
        });

        return agents.stream().map(agent -> {
            long[] agentStats = statsMap.getOrDefault(agent.getId(), new long[]{0, 0});
            long total = agentStats[0];
            long confirmed = agentStats[1];
            long cancelled = countCancelledForAgent(agent.getId());
            long doublon  = orderRepository.countByAgentAndStatus(agent.getId(), OrderStatus.DOUBLON);
            long pending = Math.max(0, total - confirmed - cancelled - doublon);
            double confirmRate = total > 0 ? round((double) confirmed / total * 100) : 0;
            double cancelRate = total > 0 ? round((double) cancelled / total * 100) : 0;

            return AgentPerformanceDto.builder()
                    .agentId(agent.getId())
                    .agentName(agent.getFullName())
                    .agentUsername(agent.getUsername())
                    .totalAssigned(total)
                    .confirmed(confirmed)
                    .cancelled(cancelled)
                    .doublon(doublon)
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
    public List<DailyStatsDto> getDailyTrendBetween(LocalDateTime from, LocalDateTime to) {
        return orderRepository.getDailyStats(from, to).stream().map(row -> {
            long total     = ((Number) row[1]).longValue();
            long confirmed = ((Number) row[2]).longValue();
            return DailyStatsDto.builder()
                    .date(row[0].toString())
                    .totalOrders(total)
                    .confirmedOrders(confirmed)
                    .confirmationRate(total > 0 ? round((double) confirmed / total * 100) : 0)
                    .revenue(BigDecimal.ZERO)
                    .build();
        }).collect(Collectors.toList());
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
            OrderStatus.ANNULE, OrderStatus.PAS_SERIEUX, OrderStatus.FAKE_ORDER);

    private static final List<OrderStatus> PENDING_STATUSES = List.of(
            OrderStatus.NOUVEAU, OrderStatus.APPEL_1, OrderStatus.APPEL_2, OrderStatus.APPEL_3,
            OrderStatus.MESSAGE_WHATSAPP, OrderStatus.PAS_DE_REPONSE,
            OrderStatus.INJOIGNABLE, OrderStatus.REPORTE);

    // All statuses that represent an order confirmed by an agent (including delivery pipeline)
    private static final List<OrderStatus> CONFIRMED_PIPELINE_STATUSES = List.of(
            OrderStatus.CONFIRME, OrderStatus.EN_PREPARATION, OrderStatus.ENVOYE,
            OrderStatus.EN_LIVRAISON, OrderStatus.LIVRE,
            OrderStatus.ECHEC_LIVRAISON, OrderStatus.RETOURNE);

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
        // "confirmed" = all orders that passed through confirmation (including delivery pipeline)
        long confirmed = orderRepository.countByAgentAndStatuses(agentId, CONFIRMED_PIPELINE_STATUSES);
        long cancelled = orderRepository.countByAgentAndStatuses(agentId, CANCELLED_STATUSES);
        long doublon   = orderRepository.countByAgentAndStatus(agentId, OrderStatus.DOUBLON);
        long delivered = orderRepository.countByAgentAndStatus(agentId, OrderStatus.LIVRE);
        long pending   = Math.max(0, total - confirmed - cancelled - doublon);
        long duplicates = orderRepository.countPotentialDuplicatesByAgent(agentId);

        double confirmRate = total > 0 ? round((double) confirmed / total * 100) : 0;
        double cancelRate  = total > 0 ? round((double) cancelled / total * 100) : 0;

        BigDecimal revenue = orderRepository.sumRevenueByAgentAndStatus(agentId, OrderStatus.LIVRE);

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
                .deliveredOrders(delivered)
                .cancelledOrders(cancelled)
                .doublonOrders(doublon)
                .pendingOrders(pending)
                .potentialDuplicates(duplicates)
                .confirmationRate(confirmRate)
                .cancellationRate(cancelRate)
                .revenue(revenue)
                .dailyTrend(trend)
                .build();
    }

    private long countCancelled() {
        return orderRepository.countByStatus(OrderStatus.ANNULE) +
               orderRepository.countByStatus(OrderStatus.PAS_SERIEUX) +
               orderRepository.countByStatus(OrderStatus.FAKE_ORDER);
    }

    private long countCancelledFiltered(LocalDateTime from, LocalDateTime to) {
        return orderRepository.countByStatusesAndDateRange(CANCELLED_STATUSES, from, to);
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

    private long countPendingFiltered(LocalDateTime from, LocalDateTime to) {
        return orderRepository.countByStatusesAndDateRange(PENDING_STATUSES, from, to);
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

    @Transactional(readOnly = true)
    public List<ProductPerformanceDto> getProductPerformance(LocalDate from, LocalDate to) {
        List<Object[]> rows = (from != null && to != null)
                ? orderItemRepository.aggregateByProductAndDateRange(
                        from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                : orderItemRepository.aggregateByProduct();

        return rows.stream().map(r -> {
            long qtyDelivered  = toLong(r[4]);
            long qtyReturned   = toLong(r[5]);
            long qtyAttempted  = qtyDelivered + qtyReturned;
            BigDecimal revenue  = toBD(r[7]);
            BigDecimal cost     = toBD(r[8]);
            BigDecimal margin   = revenue.subtract(cost);
            double deliveryRate = qtyAttempted > 0
                    ? round((double) qtyDelivered / qtyAttempted * 100)
                    : 0;
            BigDecimal avgOrderValue = qtyDelivered > 0
                    ? revenue.divide(BigDecimal.valueOf(qtyDelivered), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            return ProductPerformanceDto.builder()
                    .productId((Long) r[0])
                    .productName((String) r[1])
                    .productSku((String) r[2])
                    .totalQuantityOrdered(toLong(r[3]))
                    .quantityDelivered(qtyDelivered)
                    .quantityReturned(qtyReturned)
                    .quantityCancelled(toLong(r[6]))
                    .revenue(revenue)
                    .productCost(cost)
                    .grossMargin(margin)
                    .deliveryRate(deliveryRate)
                    .avgOrderValue(avgOrderValue)
                    .build();
        }).collect(Collectors.toList());
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private BigDecimal toBD(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }
}
