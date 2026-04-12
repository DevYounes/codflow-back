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
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    // Auto-tag as FIDELE when confirmed orders reach this threshold
    private static final int FIDELE_THRESHOLD = 3;
    // Auto-tag as NON_SERIEUX when cumulative delivery cancellations reach this threshold
    private static final int NON_SERIEUX_CANCEL_THRESHOLD = 3;
    // Un refus est un acte délibéré — seuil plus bas que les annulations
    private static final int NON_SERIEUX_REFUSAL_THRESHOLD = 2;

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

    @Transactional(readOnly = true)
    public Page<CustomerDto> getCustomers(CustomerStatus status, String search, Pageable pageable) {
        String searchPattern = (search != null && !search.isBlank())
                ? "%" + search.trim().toLowerCase() + "%"
                : null;
        return customerRepository.findWithFilters(status, searchPattern, pageable)
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

    /**
     * Appelé après chaque annulation de livraison Ozon ("Annulé").
     * Si le total des tentatives annulées atteint le seuil, le client est tagué NON_SERIEUX.
     * Ne déclasse jamais un client déjà FIDELE ou BLACKLISTED.
     *
     * @param customer        l'entité cliente (doit être MANAGED dans la transaction courante)
     * @param totalCancelled  somme de tous les cancelled_attempts sur toutes ses commandes
     */
    @Transactional
    public void recordDeliveryCancellation(Customer customer, long totalCancelled) {
        // Ne pas écraser les statuts manuels plus graves
        if (customer.getStatus() == CustomerStatus.BLACKLISTED
                || customer.getStatus() == CustomerStatus.NON_SERIEUX) {
            return;
        }
        if (totalCancelled >= NON_SERIEUX_CANCEL_THRESHOLD) {
            customer.setStatus(CustomerStatus.NON_SERIEUX);
            String note = "Flagué automatiquement : " + totalCancelled
                    + " tentative(s) de livraison annulée(s) par le transporteur.";
            customer.setNotes(note);
            customerRepository.save(customer);
            log.warn("[CLIENT NON-SERIEUX] {} (id={}) — {} annulations de livraison",
                    customer.getFullName(), customer.getId(), totalCancelled);
        }
    }

    /**
     * Appelé après chaque refus explicite d'un colis ("Refusé" chez Ozon).
     * Un refus est un acte délibéré du client (pas une absence) — seuil plus bas que les annulations.
     * Flagué NON_SERIEUX à partir de 2 refus cumulés sur toutes ses commandes.
     *
     * @param customer     l'entité cliente (doit être MANAGED dans la transaction courante)
     * @param totalRefused somme de tous les refused_attempts sur toutes ses commandes
     */
    @Transactional
    public void recordDeliveryRefusal(Customer customer, long totalRefused) {
        if (customer.getStatus() == CustomerStatus.BLACKLISTED
                || customer.getStatus() == CustomerStatus.NON_SERIEUX) {
            return;
        }
        if (totalRefused >= NON_SERIEUX_REFUSAL_THRESHOLD) {
            customer.setStatus(CustomerStatus.NON_SERIEUX);
            String note = "Flagué automatiquement : " + totalRefused
                    + " refus explicite(s) du colis à la porte (perte frais livraison).";
            customer.setNotes(note);
            customerRepository.save(customer);
            log.warn("[CLIENT NON-SERIEUX] {} (id={}) — {} refus de livraison à la porte",
                    customer.getFullName(), customer.getId(), totalRefused);
        }
    }

    /**
     * After each confirmed order, re-evaluate if customer should be tagged FIDELE.
     * Only promotes ACTIVE → FIDELE, never demotes.
     */
    @Transactional
    public void refreshFideleStatus(Long customerId) {
        customerRepository.findById(customerId).ifPresent(customer -> {
            if (customer.getStatus() != CustomerStatus.ACTIVE) return;
            long confirmed = customerRepository.countConfirmedOrdersByCustomer(customerId);
            if (confirmed >= FIDELE_THRESHOLD) {
                customer.setStatus(CustomerStatus.FIDELE);
                customerRepository.save(customer);
                log.info("Customer {} tagged as FIDELE after {} confirmed orders", customerId, confirmed);
            }
        });
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
