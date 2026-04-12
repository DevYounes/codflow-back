package com.codflow.backend.salary.service;

import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.salary.dto.CreateSalaryPaymentRequest;
import com.codflow.backend.salary.dto.PaySalaryRequest;
import com.codflow.backend.salary.dto.SalaryConfigDto;
import com.codflow.backend.salary.dto.SalaryPaymentDto;
import com.codflow.backend.salary.dto.SalaryPreviewDto;
import com.codflow.backend.salary.dto.UpdateSalaryConfigRequest;
import com.codflow.backend.salary.dto.UpdateSalaryPaymentRequest;
import com.codflow.backend.salary.entity.SalaryPayment;
import com.codflow.backend.salary.enums.CommissionType;
import com.codflow.backend.salary.enums.SalaryPaymentStatus;
import com.codflow.backend.salary.enums.SalaryType;
import com.codflow.backend.salary.repository.SalaryPaymentRepository;
import com.codflow.backend.security.UserPrincipal;
import com.codflow.backend.team.entity.User;
import com.codflow.backend.team.enums.Role;
import com.codflow.backend.team.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SalaryService {

    private final SalaryPaymentRepository salaryPaymentRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final DeliveryShipmentRepository shipmentRepository;

    // Seuls ces rôles sont éligibles à une fiche de paie
    private static final EnumSet<Role> PAYABLE_ROLES =
            EnumSet.of(Role.AGENT, Role.MAGASINIER, Role.MANAGER, Role.ADMIN);

    // =========================================================================
    // Configuration salariale
    // =========================================================================

    @Transactional(readOnly = true)
    public SalaryConfigDto getConfig(Long userId) {
        return toConfigDto(getUserOrThrow(userId));
    }

    @Transactional(readOnly = true)
    public List<SalaryConfigDto> listConfigs() {
        return userRepository.findAll().stream()
                .filter(User::isActive)
                .filter(u -> PAYABLE_ROLES.contains(u.getRole()))
                .map(this::toConfigDto)
                .toList();
    }

    @Transactional
    public SalaryConfigDto updateConfig(Long userId, UpdateSalaryConfigRequest req) {
        User user = getUserOrThrow(userId);
        validateConfig(user.getRole(), req);

        user.setSalaryType(req.getSalaryType());
        user.setFixedSalary(req.getSalaryType() == SalaryType.COMMISSION
                ? BigDecimal.ZERO
                : nvl(req.getFixedSalary()));

        if (req.getSalaryType() == SalaryType.FIXE) {
            user.setCommissionType(null);
            user.setCommissionPerConfirmed(null);
            user.setCommissionPerDelivered(null);
        } else {
            user.setCommissionType(req.getCommissionType());
            user.setCommissionPerConfirmed(
                    commissionAppliesToConfirmed(req.getCommissionType())
                            ? nvl(req.getCommissionPerConfirmed())
                            : null);
            user.setCommissionPerDelivered(
                    commissionAppliesToDelivered(req.getCommissionType())
                            ? nvl(req.getCommissionPerDelivered())
                            : null);
        }

        return toConfigDto(userRepository.save(user));
    }

    // =========================================================================
    // Aperçu de salaire
    // =========================================================================

    @Transactional(readOnly = true)
    public SalaryPreviewDto preview(Long userId, LocalDate from, LocalDate to) {
        User user = getUserOrThrow(userId);
        validatePeriod(from, to);

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();

        long confirmedCount = 0;
        long deliveredCount = 0;
        if (commissionAppliesToConfirmed(user.getCommissionType()) && user.getRole() == Role.AGENT) {
            confirmedCount = orderRepository.countConfirmedByAgentInPeriod(user.getId(), fromDt, toDt);
        }
        if (commissionAppliesToDelivered(user.getCommissionType())) {
            deliveredCount = user.getRole() == Role.AGENT
                    ? shipmentRepository.countDeliveredByAgentInPeriod(user.getId(), fromDt, toDt)
                    : shipmentRepository.countDeliveredInPeriod(fromDt, toDt);
        }

        BigDecimal commissionFromConfirmed = multiply(user.getCommissionPerConfirmed(), confirmedCount);
        BigDecimal commissionFromDelivered = multiply(user.getCommissionPerDelivered(), deliveredCount);
        BigDecimal commission = commissionFromConfirmed.add(commissionFromDelivered);
        BigDecimal fixed = user.getSalaryType() == SalaryType.COMMISSION
                ? BigDecimal.ZERO
                : nvl(user.getFixedSalary());
        BigDecimal total = fixed.add(commission);

        return SalaryPreviewDto.builder()
                .userId(user.getId())
                .fullName(user.getFullName())
                .role(user.getRole())
                .periodStart(from)
                .periodEnd(to)
                .salaryType(user.getSalaryType())
                .salaryTypeLabel(user.getSalaryType().getLabel())
                .commissionType(user.getCommissionType())
                .commissionTypeLabel(user.getCommissionType() != null ? user.getCommissionType().getLabel() : null)
                .fixedSalary(fixed)
                .commissionPerConfirmed(user.getCommissionPerConfirmed())
                .commissionPerDelivered(user.getCommissionPerDelivered())
                .confirmedCount(confirmedCount)
                .deliveredCount(deliveredCount)
                .commissionFromConfirmed(commissionFromConfirmed)
                .commissionFromDelivered(commissionFromDelivered)
                .commissionAmount(commission)
                .totalAmount(total)
                .build();
    }

    // =========================================================================
    // CRUD fiches de paie
    // =========================================================================

    @Transactional
    public SalaryPaymentDto create(CreateSalaryPaymentRequest req, UserPrincipal principal) {
        User user = getUserOrThrow(req.getUserId());
        validatePeriod(req.getPeriodStart(), req.getPeriodEnd());

        if (salaryPaymentRepository.existsActiveForUserAndPeriod(
                user.getId(), req.getPeriodStart(), req.getPeriodEnd())) {
            throw new BusinessException("Une fiche de paie existe déjà pour cet utilisateur sur cette période");
        }

        SalaryPreviewDto preview = preview(user.getId(), req.getPeriodStart(), req.getPeriodEnd());

        SalaryPayment payment = new SalaryPayment();
        payment.setUser(user);
        payment.setPeriodStart(req.getPeriodStart());
        payment.setPeriodEnd(req.getPeriodEnd());
        payment.setSalaryType(user.getSalaryType());
        payment.setCommissionType(user.getCommissionType());
        payment.setFixedSalary(preview.getFixedSalary());
        payment.setCommissionPerConfirmed(user.getCommissionPerConfirmed());
        payment.setCommissionPerDelivered(user.getCommissionPerDelivered());
        payment.setConfirmedCount((int) preview.getConfirmedCount());
        payment.setDeliveredCount((int) preview.getDeliveredCount());
        payment.setCommissionAmount(preview.getCommissionAmount());
        payment.setBonus(nvl(req.getBonus()));
        payment.setDeduction(nvl(req.getDeduction()));
        payment.setNotes(req.getNotes());
        payment.setStatus(SalaryPaymentStatus.BROUILLON);
        payment.setTotalAmount(computeTotal(payment));

        if (principal != null) {
            userRepository.findById(principal.getId()).ifPresent(payment::setCreatedBy);
        }

        return toDto(salaryPaymentRepository.save(payment));
    }

    @Transactional
    public SalaryPaymentDto update(Long id, UpdateSalaryPaymentRequest req) {
        SalaryPayment payment = getPaymentOrThrow(id);
        if (payment.getStatus() != SalaryPaymentStatus.BROUILLON) {
            throw new BusinessException("Seules les fiches en brouillon peuvent être modifiées");
        }
        if (req.getBonus() != null) payment.setBonus(req.getBonus());
        if (req.getDeduction() != null) payment.setDeduction(req.getDeduction());
        if (req.getNotes() != null) payment.setNotes(req.getNotes());
        payment.setTotalAmount(computeTotal(payment));
        return toDto(salaryPaymentRepository.save(payment));
    }

    @Transactional
    public SalaryPaymentDto markAsPaid(Long id, PaySalaryRequest req) {
        SalaryPayment payment = getPaymentOrThrow(id);
        if (payment.getStatus() == SalaryPaymentStatus.PAYE) {
            throw new BusinessException("Cette fiche est déjà payée");
        }
        if (payment.getStatus() == SalaryPaymentStatus.ANNULE) {
            throw new BusinessException("Impossible de payer une fiche annulée");
        }
        payment.setPaymentDate(req.getPaymentDate());
        payment.setPaymentMethod(req.getPaymentMethod());
        payment.setReference(req.getReference());
        if (req.getNotes() != null) payment.setNotes(req.getNotes());
        payment.setStatus(SalaryPaymentStatus.PAYE);
        return toDto(salaryPaymentRepository.save(payment));
    }

    @Transactional
    public SalaryPaymentDto cancel(Long id) {
        SalaryPayment payment = getPaymentOrThrow(id);
        if (payment.getStatus() == SalaryPaymentStatus.PAYE) {
            throw new BusinessException("Impossible d'annuler une fiche déjà payée");
        }
        payment.setStatus(SalaryPaymentStatus.ANNULE);
        return toDto(salaryPaymentRepository.save(payment));
    }

    @Transactional
    public void delete(Long id) {
        SalaryPayment payment = getPaymentOrThrow(id);
        if (payment.getStatus() == SalaryPaymentStatus.PAYE) {
            throw new BusinessException("Impossible de supprimer une fiche déjà payée — annulez-la d'abord");
        }
        salaryPaymentRepository.delete(payment);
    }

    @Transactional(readOnly = true)
    public SalaryPaymentDto get(Long id) {
        return toDto(getPaymentOrThrow(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<SalaryPaymentDto> list(Long userId,
                                               SalaryPaymentStatus status,
                                               LocalDate from,
                                               LocalDate to,
                                               Pageable pageable) {
        Specification<SalaryPayment> spec = (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null) predicates.add(cb.equal(root.get("user").get("id"), userId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("periodEnd"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("periodStart"), to));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<SalaryPayment> page = salaryPaymentRepository.findAll(spec, pageable);
        return PageResponse.of(page.map(this::toDto));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User getUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur", userId));
    }

    private SalaryPayment getPaymentOrThrow(Long id) {
        return salaryPaymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fiche de paie", id));
    }

    private void validateConfig(Role role, UpdateSalaryConfigRequest req) {
        SalaryType salaryType = req.getSalaryType();
        CommissionType commissionType = req.getCommissionType();

        if (salaryType == SalaryType.COMMISSION && role != Role.AGENT) {
            throw new BusinessException("Seuls les agents peuvent être rémunérés uniquement à la commission");
        }

        if (salaryType != SalaryType.FIXE) {
            if (commissionType == null) {
                throw new BusinessException("Le type de commission est obligatoire");
            }
            if (role == Role.MAGASINIER && commissionType != CommissionType.PAR_LIVRE) {
                throw new BusinessException(
                        "La commission des magasiniers doit obligatoirement être « par livré »");
            }
            if (commissionAppliesToConfirmed(commissionType)
                    && (req.getCommissionPerConfirmed() == null
                        || req.getCommissionPerConfirmed().signum() <= 0)) {
                throw new BusinessException("Montant de commission par confirmé obligatoire");
            }
            if (commissionAppliesToDelivered(commissionType)
                    && (req.getCommissionPerDelivered() == null
                        || req.getCommissionPerDelivered().signum() <= 0)) {
                throw new BusinessException("Montant de commission par livré obligatoire");
            }
        }

        if (salaryType == SalaryType.FIXE || salaryType == SalaryType.FIXE_PLUS_COMMISSION) {
            if (req.getFixedSalary() == null || req.getFixedSalary().signum() < 0) {
                throw new BusinessException("Le salaire fixe est obligatoire et doit être positif");
            }
        }
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException("Les dates de période sont obligatoires");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("La date de début doit être antérieure à la date de fin");
        }
    }

    private static boolean commissionAppliesToConfirmed(CommissionType t) {
        return t == CommissionType.PAR_CONFIRME || t == CommissionType.CONFIRME_ET_LIVRE;
    }

    private static boolean commissionAppliesToDelivered(CommissionType t) {
        return t == CommissionType.PAR_LIVRE || t == CommissionType.CONFIRME_ET_LIVRE;
    }

    private static BigDecimal multiply(BigDecimal unit, long count) {
        if (unit == null || count <= 0) return BigDecimal.ZERO;
        return unit.multiply(BigDecimal.valueOf(count));
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal computeTotal(SalaryPayment p) {
        return nvl(p.getFixedSalary())
                .add(nvl(p.getCommissionAmount()))
                .add(nvl(p.getBonus()))
                .subtract(nvl(p.getDeduction()));
    }

    // =========================================================================
    // Mappers
    // =========================================================================

    private SalaryConfigDto toConfigDto(User user) {
        return SalaryConfigDto.builder()
                .userId(user.getId())
                .fullName(user.getFullName())
                .role(user.getRole())
                .salaryType(user.getSalaryType())
                .salaryTypeLabel(user.getSalaryType() != null ? user.getSalaryType().getLabel() : null)
                .fixedSalary(user.getFixedSalary())
                .commissionType(user.getCommissionType())
                .commissionTypeLabel(user.getCommissionType() != null ? user.getCommissionType().getLabel() : null)
                .commissionPerConfirmed(user.getCommissionPerConfirmed())
                .commissionPerDelivered(user.getCommissionPerDelivered())
                .build();
    }

    private SalaryPaymentDto toDto(SalaryPayment p) {
        return SalaryPaymentDto.builder()
                .id(p.getId())
                .userId(p.getUser() != null ? p.getUser().getId() : null)
                .userFullName(p.getUser() != null ? p.getUser().getFullName() : null)
                .userRole(p.getUser() != null ? p.getUser().getRole() : null)
                .periodStart(p.getPeriodStart())
                .periodEnd(p.getPeriodEnd())
                .salaryType(p.getSalaryType())
                .salaryTypeLabel(p.getSalaryType() != null ? p.getSalaryType().getLabel() : null)
                .commissionType(p.getCommissionType())
                .commissionTypeLabel(p.getCommissionType() != null ? p.getCommissionType().getLabel() : null)
                .fixedSalary(p.getFixedSalary())
                .commissionPerConfirmed(p.getCommissionPerConfirmed())
                .commissionPerDelivered(p.getCommissionPerDelivered())
                .confirmedCount(p.getConfirmedCount())
                .deliveredCount(p.getDeliveredCount())
                .commissionAmount(p.getCommissionAmount())
                .bonus(p.getBonus())
                .deduction(p.getDeduction())
                .totalAmount(p.getTotalAmount())
                .status(p.getStatus())
                .statusLabel(p.getStatus() != null ? p.getStatus().getLabel() : null)
                .paymentDate(p.getPaymentDate())
                .paymentMethod(p.getPaymentMethod())
                .paymentMethodLabel(p.getPaymentMethod() != null ? p.getPaymentMethod().getLabel() : null)
                .reference(p.getReference())
                .notes(p.getNotes())
                .createdByName(p.getCreatedBy() != null ? p.getCreatedBy().getFullName() : null)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
