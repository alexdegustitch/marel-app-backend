package com.aleksandarparipovic.marel_app.bootstrap;

import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the internal engineering account on a database that has none.
 *
 * <p>It exists because "developer" is the only role that may read
 * {@code /actuator} (see {@code SecurityConfig}), and the moment that access is
 * actually needed is the moment production is misbehaving — a bad time to
 * discover the account was never created.
 *
 * <p><b>Seeded only when a password is supplied.</b> Unlike the bootstrap admin,
 * whose password is a required property, this one is skipped when
 * {@code app.bootstrap.developer.password} is empty. A default password would put
 * the same known credentials on every installation, on an account that holds
 * every permission there is — that is a back door, not a convenience. Local and
 * test setups therefore simply have no developer account until someone asks for
 * one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeveloperInitializer {

    private static final String ROLE_NAME = "developer";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.developer.username:developer}")
    private String developerUsername;

    @Value("${app.bootstrap.developer.email:developer@marel.local}")
    private String developerEmail;

    @Value("${app.bootstrap.developer.first-name:System}")
    private String developerFirstName;

    @Value("${app.bootstrap.developer.last-name:Developer}")
    private String developerLastName;

    @Value("${app.bootstrap.developer.password:}")
    private String developerPassword;

    @PostConstruct
    public void init() {
        if (developerPassword == null || developerPassword.isBlank()) {
            log.info("[DeveloperInitializer] No bootstrap password configured — "
                    + "developer account not created. /actuator will have no reader.");
            return;
        }

        if (userRepository.existsByUsername(developerUsername)) {
            return;
        }

        // The role may legitimately be missing on a database whose reference data
        // predates it; creating it here keeps the account from failing on a
        // technicality. It grants nothing by itself — RolePermissions decides that.
        Role developerRole = roleRepository.findByRoleNameIgnoreCase(ROLE_NAME)
                .orElseGet(() -> roleRepository.save(
                        Role.builder()
                                .roleName(ROLE_NAME)
                                .build()));

        userRepository.save(User.builder()
                .username(developerUsername)
                .firstName(developerFirstName)
                .lastName(developerLastName)
                .emailAddress(developerEmail)
                .passwordHash(passwordEncoder.encode(developerPassword))
                .role(developerRole)
                .accountStatus(UserAccountStatus.ACTIVE)
                .active(true)
                .build());

        log.info("[DeveloperInitializer] Developer account created: {}", developerUsername);
    }
}
