package com.codflow.backend.config;

import com.codflow.backend.team.entity.User;
import com.codflow.backend.team.enums.Role;
import com.codflow.backend.team.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        createAdminIfNotExists();
    }

    private void createAdminIfNotExists() {
        if (userRepository.existsByUsername("admin")) {
            return;
        }
        User admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@codflow.com");
        admin.setPassword(passwordEncoder.encode("Admin@123"));
        admin.setFirstName("Admin");
        admin.setLastName("CODFlow");
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        userRepository.save(admin);
        log.info("Admin user created: admin / Admin@123");
    }
}
