package com.codflow.backend.charges.service;

import com.codflow.backend.charges.dto.BusinessChargeDto;
import com.codflow.backend.charges.dto.BusinessProfitSummaryDto;
import com.codflow.backend.charges.dto.CreateBusinessChargeRequest;
import com.codflow.backend.charges.entity.BusinessCharge;
import com.codflow.backend.charges.enums.BusinessChargeType;
import com.codflow.backend.charges.repository.BusinessChargeRepository;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.delivery.entity.DeliveryShipment;
import com.codflow.backend.delivery.enums.ShipmentStatus;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import com.codflow.backend.order.enums.OrderStatus;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.security.UserPrincipal;
import com.codflow.backend.team.repository.UserRepository;
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
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BusinessChargesService {

    private final BusinessChargeRepository chargeRepository;
    private final DeliveryShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    // -------------------------------------------------------------------------
    // CRUD charges opérationnelles
    // -------------------------------------------------------------------------

    @Transactional
    public BusinessChargeDto addCharge(CreateBusinessChargeRequest req, UserPrincipal principal) {
        BusinessCharge charge = new BusinessCharge();
        charge.setType(req.getType());
        charge.setLabel(req.getLabel());
        charge.setAmount(req.getAmount());
        charge.setChargeDate(req.getChargeDate());
        charge.setNotes(req.getNotes());
        if (principal != null) {
            userRepository.findById(principal.getId()).ifPresent(charge::setCreatedBy);
        }
        return toDto(chargeRepository.save(charge));
    }

    @Transactional
    public BusinessChargeDto updateCharge(Long id, CreateBusinessChargeRequest req) {
        BusinessCharge charge = chargeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Charge", id));
        charge.setType(req.getType());
        charge.setLabel(req.getLabel());
        charge.setAmount(req.getAmount());
        charge.setChargeDate(req.getChargeDate());
        charge.setNotes(req.getNotes());
        return toDto(chargeRepository.save(charge));
    }

    @Transactional
    public void deleteCharge(Long id) {
        if (!chargeRepository.existsById(id)) throw new ResourceNotFoundException("Charge", id);
        chargeRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public Page<BusinessChargeDto> listCharges(LocalDate from, LocalDate to,
                                                BusinessChargeType type, Pageable pageable) {
        Specification<BusinessCharge> spec = buildSpec(from, to, type);
        return chargeRepository.findAll(spec, pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public BusinessChargeDto getCharge(Long id) {
        return toDto(chargeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Charge", id)));
    }

    // -------------------------------------------------------------------------
    // Récapitulatif complet du profit COD
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BusinessProfitSummaryDto getProfitSummary(LocalDate from, LocalDate to) {
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();
        LocalDateTime fromDt = effectiveFrom.atStartOfDay();
        LocalDateTime toDt   = effectiveTo.plusDays(1).atStartOfDay();

        // --- Volumes commandes ---
        long totalOrders = orderRepository.countByDateRange(fromDt, toDt);
        long deliveredOrders = orderRepository.countByStatusAndDateRange(
                OrderStatus.LIVRE, fromDt, toDt);
        long returnedOrders = orderRepository.countByStatusAndDateRange(
                OrderStatus.RETOURNE, fromDt, toDt);
        long cancelledOrders = orderRepository.countByStatusesAndDateRange(
                EnumSet.of(OrderStatus.ANNULE, OrderStatus.PAS_SERIEUX, OrderStatus.FAKE_ORDER),
                fromDt, toDt);
        double deliveryRate = totalOrders > 0
                ? Math.round((double) deliveredOrders / totalOrders * 10000.0) / 100.0
                : 0;

        // --- CA & coûts produits (depuis les colis livrés) ---
        List<DeliveryShipment> deliveredShipments = shipmentRepository.findAll(
                (root, q, cb) -> cb.and(
                        cb.equal(root.get("status"), ShipmentStatus.DELIVERED),
                        cb.greaterThanOrEqualTo(root.get("createdAt"), fromDt),
                        cb.lessThan(root.get("createdAt"), toDt)
                ));

        BigDecimal revenue = deliveredShipments.stream()
                .map(s -> nvl(s.getOrder().getTotalAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal productCosts = deliveredShipments.stream()
                .map(s -> s.getOrder().getItems().stream()
                        .filter(i -> i.getUnitCost() != null)
                        .map(i -> i.getUnitCost().multiply(BigDecimal.valueOf(i.getQuantity())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // --- Frais livreur (tous statuts finaux) ---
        List<DeliveryShipment> allShipments = shipmentRepository.findAll(
                (root, q, cb) -> cb.and(
                        cb.greaterThanOrEqualTo(root.get("createdAt"), fromDt),
                        cb.lessThan(root.get("createdAt"), toDt)
                ));

        BigDecimal deliveryCharges = allShipments.stream()
                .filter(s -> s.getAppliedFee() != null)
                .map(DeliveryShipment::getAppliedFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossMargin = revenue.subtract(productCosts).subtract(deliveryCharges);
        BigDecimal grossMarginRate = revenue.compareTo(BigDecimal.ZERO) > 0
                ? grossMargin.divide(revenue, 4, RoundingMode.HALF_UP)
                             .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // --- Charges opérationnelles (saisies manuellement) ---
        BigDecimal pubCosts      = sumByType(BusinessChargeType.PUBLICITE,   effectiveFrom, effectiveTo);
        BigDecimal salaryCosts   = sumByType(BusinessChargeType.SALAIRE,     effectiveFrom, effectiveTo);
        BigDecimal rentCosts     = sumByType(BusinessChargeType.LOYER,       effectiveFrom, effectiveTo);
        BigDecimal utilitiesCosts = sumByType(BusinessChargeType.ELECTRICITE, effectiveFrom, effectiveTo)
                .add(sumByType(BusinessChargeType.EAU,      effectiveFrom, effectiveTo))
                .add(sumByType(BusinessChargeType.INTERNET, effectiveFrom, effectiveTo));
        BigDecimal otherCosts = sumByType(BusinessChargeType.TRANSPORT,  effectiveFrom, effectiveTo)
                .add(sumByType(BusinessChargeType.EMBALLAGE, effectiveFrom, effectiveTo))
                .add(sumByType(BusinessChargeType.AUTRE,    effectiveFrom, effectiveTo));
        BigDecimal totalOperationalCosts = pubCosts.add(salaryCosts).add(rentCosts)
                .add(utilitiesCosts).add(otherCosts);

        // --- Gain net ---
        BigDecimal netProfit = grossMargin.subtract(totalOperationalCosts);
        BigDecimal netMarginRate = revenue.compareTo(BigDecimal.ZERO) > 0
                ? netProfit.divide(revenue, 4, RoundingMode.HALF_UP)
                           .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // --- Métriques pub ---
        BigDecimal costPerLead = totalOrders > 0 && pubCosts.compareTo(BigDecimal.ZERO) > 0
                ? pubCosts.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal costPerDelivery = deliveredOrders > 0 && pubCosts.compareTo(BigDecimal.ZERO) > 0
                ? pubCosts.divide(BigDecimal.valueOf(deliveredOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal revenuePerDelivery = deliveredOrders > 0
                ? revenue.divide(BigDecimal.valueOf(deliveredOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return BusinessProfitSummaryDto.builder()
                .from(effectiveFrom)
                .to(effectiveTo)
                .totalOrders(totalOrders)
                .deliveredOrders(deliveredOrders)
                .returnedOrders(returnedOrders)
                .cancelledOrders(cancelledOrders)
                .deliveryRate(deliveryRate)
                .revenue(revenue)
                .productCosts(productCosts)
                .deliveryCharges(deliveryCharges)
                .grossMargin(grossMargin)
                .grossMarginRate(grossMarginRate)
                .pubCosts(pubCosts)
                .salaryCosts(salaryCosts)
                .rentCosts(rentCosts)
                .utilitiesCosts(utilitiesCosts)
                .otherCosts(otherCosts)
                .totalOperationalCosts(totalOperationalCosts)
                .netProfit(netProfit)
                .netMarginRate(netMarginRate)
                .costPerLead(costPerLead)
                .costPerDelivery(costPerDelivery)
                .revenuePerDelivery(revenuePerDelivery)
                .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BigDecimal sumByType(BusinessChargeType type, LocalDate from, LocalDate to) {
        return chargeRepository.sumByTypeAndPeriod(type, from, to);
    }

    private BigDecimal nvl(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private Specification<BusinessCharge> buildSpec(LocalDate from, LocalDate to, BusinessChargeType type) {
        return (root, q, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("chargeDate"), from));
            if (to   != null) predicates.add(cb.lessThanOrEqualTo(root.get("chargeDate"), to));
            if (type != null) predicates.add(cb.equal(root.get("type"), type));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private BusinessChargeDto toDto(BusinessCharge c) {
        return BusinessChargeDto.builder()
                .id(c.getId())
                .type(c.getType())
                .typeLabel(c.getType().getLabel())
                .label(c.getLabel())
                .amount(c.getAmount())
                .chargeDate(c.getChargeDate())
                .notes(c.getNotes())
                .createdByName(c.getCreatedBy() != null ? c.getCreatedBy().getFullName() : null)
                .createdAt(c.getCreatedAt())
                .build();
    }
}
