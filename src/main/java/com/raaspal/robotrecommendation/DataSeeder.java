package com.raaspal.robotrecommendation;

import com.raaspal.robotrecommendation.common.enums.Role;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private static final String ADMIN_EMAIL = "admin@raaspal.com";
    private static final String ADMIN_PASSWORD = "Admin@1234";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmailIgnoreCase(ADMIN_EMAIL)) {
            log.info("Default admin already exists — skipping seed.");
            return;
        }

        User admin = User.builder()
                .email(ADMIN_EMAIL)
                .password(passwordEncoder.encode(ADMIN_PASSWORD))
                .fullName("RAASPAL Admin")
                .role(Role.ADMIN)
                .build();

        userRepository.save(admin);
        log.info("Default admin created: email={}", ADMIN_EMAIL);
    }
}
