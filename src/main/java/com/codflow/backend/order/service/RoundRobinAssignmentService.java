package com.codflow.backend.order.service;

import com.codflow.backend.config.service.SystemSettingService;
import com.codflow.backend.order.entity.Order;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.team.entity.User;
import com.codflow.backend.team.enums.Role;
import com.codflow.backend.team.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Assigns orders to agents using a round-robin strategy.
 *
 * Rules:
 *  - Only active agents (role=AGENT, active=true) are eligible.
 *  - The pointer (last assigned index) is persisted in SystemSetting so it
 *    survives restarts.
 *  - If no active agents exist, the order is left unassigned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoundRobinAssignmentService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final SystemSettingService settingService;

    /**
     * Assigns the given order to the next active agent in round-robin.
     */
    @Transactional
    public void assign(Order order) {
        List<User> activeAgents = userRepository.findByRoleAndActiveTrue(Role.AGENT);
        if (activeAgents.isEmpty()) {
            log.warn("No active agents available — order {} left unassigned", order.getOrderNumber());
            return;
        }

        // Sort agents by ID for a stable, deterministic order
        activeAgents.sort((a, b) -> Long.compare(a.getId(), b.getId()));

        int lastIndex = settingService.getInt(SystemSettingService.KEY_ROUND_ROBIN_POINTER, -1);
        int nextIndex = (lastIndex + 1) % activeAgents.size();

        User agent = activeAgents.get(nextIndex);
        order.setAssignedTo(agent);
        order.setAssignedAt(LocalDateTime.now());
        orderRepository.save(order);

        settingService.set(SystemSettingService.KEY_ROUND_ROBIN_POINTER, String.valueOf(nextIndex));
        log.info("Order {} auto-assigned to agent {} (index {})",
                order.getOrderNumber(), agent.getUsername(), nextIndex);
    }
}
