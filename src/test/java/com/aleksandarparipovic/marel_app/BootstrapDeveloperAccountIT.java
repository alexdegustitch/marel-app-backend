package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.bootstrap.DeveloperInitializer;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The internal engineering account exists on a freshly migrated database.
 *
 * <p>Worth a test because the account is only ever needed when something is
 * already wrong in production, and the failure mode is silent: nothing complains
 * about a missing developer until somebody tries to read /actuator and cannot.
 */
@Transactional
class BootstrapDeveloperAccountIT extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeveloperInitializer developerInitializer;

    @Test
    @DisplayName("startup seeds an active developer able to sign in")
    void developerIsSeeded() {
        User developer = userRepository.findByUsernameIgnoreCase("developer").orElseThrow();

        assertThat(developer.getRole().getRoleName()).isEqualToIgnoringCase("developer");
        assertThat(developer.getAccountStatus()).isEqualTo(UserAccountStatus.ACTIVE);
        assertThat(developer.getActive()).isTrue();
        // Stored hashed, never as typed — the property that seeds it is plain text.
        assertThat(developer.getPasswordHash()).isNotBlank().doesNotContain("Test1234!");
    }

    @Test
    @DisplayName("running again on a database that already has one changes nothing")
    void seedingIsIdempotent() {
        User before = userRepository.findByUsernameIgnoreCase("developer").orElseThrow();

        developerInitializer.init();

        assertThat(userRepository.findAll().stream()
                .filter(u -> "developer".equalsIgnoreCase(u.getUsername()))
                .count()).isEqualTo(1);
        assertThat(userRepository.findByUsernameIgnoreCase("developer").orElseThrow().getId())
                .isEqualTo(before.getId());
    }
}
