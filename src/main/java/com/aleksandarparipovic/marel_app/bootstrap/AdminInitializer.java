package com.aleksandarparipovic.marel_app.bootstrap;

import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminInitializer {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin.username:admin}")
    private String adminUsername;

    @Value("${app.bootstrap.admin.email:admin@marel.local}")
    private String adminEmail;

    @Value("${app.bootstrap.admin.first-name:System}")
    private String adminFirstName;

    @Value("${app.bootstrap.admin.last-name:Administrator}")
    private String adminLastName;

    @Value("${app.bootstrap.admin.password}")
    private String adminPassword;

    @PostConstruct
    public void init() {

        // 1️⃣ Ensure ADMIN role exists
        Role adminRole = roleRepository.findByRoleNameIgnoreCase("admin")
                .orElseGet(() ->
                        roleRepository.save(
                                Role.builder()
                                        .roleName("admin")
                                        .build()
                        )
                );

        // 2️⃣ Admin already exists → do nothing
        if (userRepository.existsByUsername(adminUsername)) {
            return;
        }

        // 3️⃣ Create admin user
        User admin = User.builder()
                .username(adminUsername)
                .firstName(adminFirstName)
                .lastName(adminLastName)
                .emailAddress(adminEmail)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .role(adminRole)
                .accountStatus(com.aleksandarparipovic.marel_app.user.UserAccountStatus.ACTIVE)
                .active(true)
                .build();

        userRepository.save(admin);

        System.out.println("✅ Bootstrap admin user created: " + adminUsername);
    }
}
