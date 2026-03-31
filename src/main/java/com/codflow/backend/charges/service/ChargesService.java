package com.codflow.backend.charges.service;

import com.codflow.backend.charges.dto.DailyChargesDto;
import com.codflow.backend.charges.dto.DeliveryChargesSummaryDto;
import com.codflow.backend.charges.dto.ShipmentChargeDto;
import com.codflow.backend.delivery.entity.DeliveryShipment;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChargesService {

    private final DeliveryShipmentRepository shipmentRepository;

    @Transactional(readOnly = true)
    public DeliveryChargesSummaryDto getDeliverySummary(LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from != null ? from.atStartOfDay()         : null;
        LocalDateTime toDt   = to   != null ? to.plusDays(1).atStartOfDay() : null;

        List<DeliveryShipment> shipments = shipmentRepository.findAll(
                buildSpec(fromDt, toDt));

        long delivered   = count(shipments, ShipmentStatus.DELIVERED);
        long returned    = count(shipments, ShipmentStatus.RETURNED);
        long cancelled   = count(shipments, ShipmentStatus.CANCELLED);
        long pending     = shipments.size() - delivered - returned - cancelled;

        BigDecimal deliveryCharges     = sum(shipments, "LIVRAISON");
        BigDecimal returnCharges       = sum(shipments, "RETOUR");
        BigDecimal refusedCharges      = sum(shipments, "REFUS");
        BigDecimal cancellationCharges = sum(shipments, "ANNULATION");
        BigDecimal totalCharges        = deliveryCharges.add(returnCharges)
                                                        .add(refusedCharges)
                                                        .add(cancellationCharges);

        BigDecimal avgDeliveryFee = avg(shipments.stream()
                .map(DeliveryShipment::getDeliveredPrice)
                .filter(f -> f != null && f.compareTo(BigDecimal.ZERO) > 0)
                .toList());

        BigDecimal avgReturnFee = avg(shipments.stream()
                .map(DeliveryShipment::getReturnedPrice)
                .filter(f -> f != null && f.compareTo(BigDecimal.ZERO) > 0)
                .toList());

        // Coûts produits + CA sur les commandes livrées
        List<DeliveryShipment> deliveredShipments = shipments.stream()
                .filter(s -> s.getStatus() == ShipmentStatus.DELIVERED)
                .toList();

        BigDecimal productCosts = deliveredShipments.stream()
                .map(s -> s.getOrder().getItems().stream()
                        .filter(item -> item.getUnitCost() != null)
                        .map(item -> item.getUnitCost().multiply(BigDecimal.valueOf(item.getQuantity())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal revenue = deliveredShipments.stream()
                .map(s -> s.getOrder().getTotalAmount() != null ? s.getOrder().getTotalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal netProfit = revenue.subtract(totalCharges).subtract(productCosts);

        return DeliveryChargesSummaryDto.builder()
                .from(from)
                .to(to)
                .totalShipments(shipments.size())
                .delivered(delivered)
                .returned(returned)
                .cancelled(cancelled)
                .pending(pending)
                .totalCharges(totalCharges)
                .deliveryCharges(deliveryCharges)
                .returnCharges(returnCharges)
                .refusedCharges(refusedCharges)
                .cancellationCharges(cancellationCharges)
                .avgDeliveryFee(avgDeliveryFee)
                .avgReturnFee(avgReturnFee)
                .productCosts(productCosts)
                .revenue(revenue)
                .netProfit(netProfit)
                .build();
    }

    @Transactional(readOnly = true)
    public Page<ShipmentChargeDto> getDeliveryDetails(LocalDate from, LocalDate to,
                                                       String feeType, Pageable pageable) {
        LocalDateTime fromDt = from != null ? from.atStartOfDay()         : null;
        LocalDateTime toDt   = to   != null ? to.plusDays(1).atStartOfDay() : null;

        Specification<DeliveryShipment> spec = buildSpec(fromDt, toDt);
        if (feeType != null && !feeType.isBlank()) {
            spec = spec.and((root, q, cb) ->
                    cb.equal(root.get("appliedFeeType"), feeType.toUpperCase()));
        }

        return shipmentRepository.findAll(spec, pageable).map(this::toChargeDto);
    }

    @Transactional(readOnly = true)
    public List<DailyChargesDto> getChargesDaily(int days) {
        LocalDateTime fromDt = LocalDate.now().minusDays(days - 1L).atStartOfDay();
        LocalDateTime toDt   = LocalDate.now().plusDays(1).atStartOfDay();

        List<DeliveryShipment> shipments = shipmentRepository.findAll(buildSpec(fromDt, toDt));

        return shipments.stream()
                .collect(Collectors.groupingBy(s -> s.getCreatedAt().toLocalDate()))
                .entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey()))
                .map(e -> {
                    LocalDate date = e.getKey();
                    List<DeliveryShipment> daily = e.getValue();
                    BigDecimal delivery     = sum(daily, "LIVRAISON");
                    BigDecimal ret          = sum(daily, "RETOUR");
                    BigDecimal refused      = sum(daily, "REFUS");
                    BigDecimal cancellation = sum(daily, "ANNULATION");
                    BigDecimal total = delivery.add(ret).add(refused).add(cancellation);
                    return DailyChargesDto.builder()
                            .date(date.toString())
                            .totalCharges(total)
                            .deliveryCharges(delivery)
                            .returnCharges(ret)
                            .refusedCharges(refused)
                            .cancellationCharges(cancellation)
                            .shipmentCount(daily.size())
                            .build();
                })
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Specification<DeliveryShipment> buildSpec(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to   != null) predicates.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private long count(List<DeliveryShipment> list, ShipmentStatus status) {
        return list.stream().filter(s -> s.getStatus() == status).count();
    }

    private BigDecimal sum(List<DeliveryShipment> list, String feeType) {
        return list.stream()
                .map(s -> resolveAppliedFee(s, feeType))
                .filter(f -> f != null && f.compareTo(BigDecimal.ZERO) != 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Returns the applied fee for the given feeType.
     * If appliedFeeType is already set (new shipments), use appliedFee directly.
     * If not set yet (shipments created before charges feature), derive from status + price snapshot.
     */
    private BigDecimal resolveAppliedFee(DeliveryShipment s, String feeType) {
        if (s.getAppliedFeeType() != null) {
            return feeType.equals(s.getAppliedFeeType()) ? s.getAppliedFee() : null;
        }
        // Fallback for shipments without appliedFeeType (created before charges feature)
        return switch (feeType) {
            case "LIVRAISON"  -> s.getStatus() == ShipmentStatus.DELIVERED     ? s.getDeliveredPrice() : null;
            case "RETOUR"     -> s.getStatus() == ShipmentStatus.RETURNED       ? s.getReturnedPrice()  : null;
            case "REFUS"      -> s.getStatus() == ShipmentStatus.FAILED_DELIVERY ? s.getRefusedPrice()  : null;
            case "ANNULATION" -> s.getStatus() == ShipmentStatus.CANCELLED      ? BigDecimal.ZERO       : null;
            default           -> null;
        };
    }

    private BigDecimal avg(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private ShipmentChargeDto toChargeDto(DeliveryShipment s) {
        LocalDateTime finalizedAt = s.getDeliveredAt() != null ? s.getDeliveredAt() : s.getReturnedAt();
        return ShipmentChargeDto.builder()
                .shipmentId(s.getId())
                .orderNumber(s.getOrder().getOrderNumber())
                .customerName(s.getOrder().getCustomerName())
                .trackingNumber(s.getTrackingNumber())
                .shipmentStatus(s.getStatus().name())
                .deliveredPrice(s.getDeliveredPrice())
                .returnedPrice(s.getReturnedPrice())
                .refusedPrice(s.getRefusedPrice())
                .appliedFee(s.getAppliedFee())
                .appliedFeeType(s.getAppliedFeeType())
                .finalizedAt(finalizedAt)
                .createdAt(s.getCreatedAt())
                .build();
    }
}
