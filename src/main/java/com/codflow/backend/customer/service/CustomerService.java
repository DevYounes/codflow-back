package com.codflow.backend.customer.service;

import com.codflow.backend.common.exception.ResourceNotFoundException;
import com.codflow.backend.customer.dto.CustomerDto;
import com.codflow.backend.customer.dto.UpdateCustomerRequest;
import com.codflow.backend.customer.entity.Customer;
import com.codflow.backend.customer.enums.CustomerStatus;
import com.codflow.backend.customer.repository.CustomerRepository;
import com.codflow.backend.delivery.repository.DeliveryShipmentRepository;
import com.codflow.backend.order.entity.Order;
import com.codflow.backend.order.entity.OrderItem;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final DeliveryShipmentRepository shipmentRepository;
    private final StockService stockService;

    /**
     * Returns or creates a customer by normalized phone.
     * Called from OrderService when a new order is created.
     */
    @Transactional
    public Customer findOrCreate(String phone, String phoneNormalized,
                                 String fullName, String address, String ville) {
        String lookupKey = phoneNormalized != null ? phoneNormalized : phone;

        if (phoneNormalized != null) {
            return customerRepository.findByPhoneNormalized(phoneNormalized)
                    .orElseGet(() -> create(phone, phoneNormalized, fullName, address, ville));
        }
        return customerRepository.findByPhone(phone)
                .orElseGet(() -> create(phone, null, fullName, address, ville));
    }

    private static final Set<String> ENTITY_SORT_FIELDS = Set.of(
            "id", "fullName", "phone", "email", "ville", "status", "createdAt", "updatedAt");

    @Transactional(readOnly = true)
    public Page<CustomerDto> getCustomers(CustomerStatus status, String search,
                                          int page, int size, String sortBy, String sortDir) {
        String searchPattern = (search != null && !search.isBlank())
                ? "%" + search.trim().toLowerCase() + "%"
                : null;
        boolean asc = "asc".equalsIgnoreCase(sortDir);
        String statusStr = status != null ? status.name() : null;
        Pageable unsorted = PageRequest.of(page, size);

        if ("totalOrders".equals(sortBy)) {
            return (asc
                    ? customerRepository.findSortedByTotalOrdersAsc(statusStr, searchPattern, unsorted)
                    : customerRepository.findSortedByTotalOrdersDesc(statusStr, searchPattern, unsorted))
                    .map(this::toDto);
        }
        if ("confirmationRate".equals(sortBy) || "confirmedOrders".equals(sortBy)) {
            return (asc
                    ? customerRepository.findSortedByConfirmationRateAsc(statusStr, searchPattern, unsorted)
                    : customerRepository.findSortedByConfirmationRateDesc(statusStr, searchPattern, unsorted))
                    .map(this::toDto);
        }

        String safeSortBy = ENTITY_SORT_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Sort sort = asc ? Sort.by(safeSortBy).ascending() : Sort.by(safeSortBy).descending();
        return customerRepository.findWithFilters(status, searchPattern, PageRequest.of(page, size, sort))
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public CustomerDto getCustomer(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client", id));
        return toDto(customer);
    }

    @Transactional
    public CustomerDto update(Long id, UpdateCustomerRequest request) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client", id));

        if (request.getFullName() != null && !request.getFullName().isBlank())
            customer.setFullName(request.getFullName());
        if (request.getEmail() != null)
            customer.setEmail(request.getEmail());
        if (request.getAddress() != null)
            customer.setAddress(request.getAddress());
        if (request.getVille() != null)
            customer.setVille(request.getVille());
        if (request.getStatus() != null)
            customer.setStatus(request.getStatus());
        if (request.getNotes() != null)
            customer.setNotes(request.getNotes());

        return toDto(customerRepository.save(customer));
    }

    /**
     * Convenience endpoint: change only the status + optional note.
     */
    @Transactional
    public CustomerDto updateStatus(Long id, CustomerStatus status, String notes) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client", id));
        customer.setStatus(status);
        if (notes != null) customer.setNotes(notes);
        return toDto(customerRepository.save(customer));
    }

    @Transactional
    public void deleteCustomer(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client", id));
        String customerName = customer.getFullName();

        // 1. Trouver TOUS les IDs de commandes du client (y compris soft-deleted, bypass @SQLRestriction)
        List<Long> orderIds = orderRepository.findAllOrderIdsByCustomerId(id);
        log.info("Suppression client {} (id={}) — {} commande(s) à purger", customerName, id, orderIds.size());

        if (!orderIds.isEmpty()) {
            // 2. Collecter les données stock avant tout @Modifying (qui vide le L1 cache)
            List<Order> reservedOrders = orderRepository.findReservedOrdersWithItemsByIds(orderIds);
            record StockOp(Long productId, Long variantId, int qty, Long orderId) {}
            List<StockOp> stockOps = new ArrayList<>();
            for (Order order : reservedOrders) {
                for (OrderItem item : order.getItems()) {
                    if (item.getProduct() != null) {
                        stockOps.add(new StockOp(
                                item.getProduct().getId(),
                                item.getVariant() != null ? item.getVariant().getId() : null,
                                item.getQuantity(),
                                order.getId()));
                    }
                }
            }

            // 3. Libérer les réservations stock
            for (StockOp op : stockOps) {
                stockService.releaseReservation(op.productId(), op.variantId(), op.qty(), op.orderId(),
                        "Suppression client " + customerName);
            }

            // 4. Supprimer les liens delivery_note_shipments + les shipments
            shipmentRepository.deleteNoteShipmentLinksByOrderIds(orderIds);
            shipmentRepository.deleteAllByOrderIds(orderIds);

            // 5. Vider les références source_order_id pointant vers ces commandes
            orderRepository.clearSourceOrderReferences(orderIds);

            // 6. Supprimer les enfants des commandes (JPA cascade ne s'applique pas aux DELETE natifs)
            orderRepository.deleteStatusHistoryByOrderIds(orderIds);
            orderRepository.deleteItemsByOrderIds(orderIds);

            // 7. Hard-delete toutes les commandes (bypass @SQLRestriction)
            orderRepository.hardDeleteAllByCustomerId(id);
        }

        // 8. Supprimer le client
        customerRepository.delete(customer);
        log.info("Client {} (id={}) supprimé avec {} commande(s)", customerName, id, orderIds.size());
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private CustomerDto toDto(Customer c) {
        long total     = customerRepository.countOrdersByCustomer(c.getId());
        long confirmed = customerRepository.countConfirmedOrdersByCustomer(c.getId());
        long cancelled = customerRepository.countCancelledOrdersByCustomer(c.getId());
        double rate = total > 0
                ? BigDecimal.valueOf((double) confirmed / total * 100)
                             .setScale(2, RoundingMode.HALF_UP).doubleValue()
                : 0;

        return CustomerDto.builder()
                .id(c.getId())
                .fullName(c.getFullName())
                .phone(c.getPhone())
                .email(c.getEmail())
                .address(c.getAddress())
                .ville(c.getVille())
                .status(c.getStatus())
                .statusLabel(c.getStatus().getLabel())
                .notes(c.getNotes())
                .createdAt(c.getCreatedAt())
                .totalOrders(total)
                .confirmedOrders(confirmed)
                .cancelledOrders(cancelled)
                .confirmationRate(rate)
                .lastOrderDate(customerRepository.lastOrderDateByCustomer(c.getId()))
                .build();
    }

    private Customer create(String phone, String phoneNormalized,
                            String fullName, String address, String ville) {
        Customer c = new Customer();
        c.setPhone(phone);
        c.setPhoneNormalized(phoneNormalized);
        c.setFullName(fullName != null ? fullName : "Client inconnu");
        c.setAddress(address);
        c.setVille(ville);
        c.setStatus(CustomerStatus.ACTIVE);
        return customerRepository.save(c);
    }
}
