package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.auth.AuthService;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signing in works with the username or the e-mail address, in any case.
 *
 * <p>WHY THIS EXISTS. The sign-in form asks for one field and people put either in it. For
 * most accounts here that never mattered, because they were created with the e-mail AS the
 * username — but the bootstrap admin is "admin" with the e-mail "admin@marel.local", and an
 * exact username lookup turned typing that account's own e-mail into "invalid username or
 * password". It looked like a broken password on one account and nothing else, which is the
 * most misleading shape a bug can take.
 *
 * <p>Case is ignored because the database already guarantees it is not meaningful: the unique
 * indexes are on {@code lower(username)} and {@code lower(email_address)}, so "Admin" and
 * "admin" can never be two people. The lookup used to be stricter than the schema.
 */
@Transactional
class SignInAcceptsUsernameOrEmailIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String PASSWORD = "Test1234!";

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("the username, the e-mail, and either in any case, all sign the same person in")
    void usernameOrEmailBothWork() {
        int n = COUNTER.incrementAndGet();
        String username = "admin-" + n + "-" + System.nanoTime();
        String email = "admin" + n + "-" + System.nanoTime() + "@marel.local";
        seedUser(username, email);

        // The account's own username and its own e-mail are both what it answers to.
        assertThat(signIn(username)).isTrue();
        assertThat(signIn(email)).isTrue();

        // Case is not meaningful — the schema's unique indexes already say so.
        assertThat(signIn(username.toUpperCase())).isTrue();
        assertThat(signIn(email.toUpperCase())).isTrue();

        // A space pasted along with the value is not a different account.
        assertThat(signIn("  " + username + "  ")).isTrue();
    }

    @Test
    @DisplayName("a wrong password and an unknown account are refused the same way")
    void wrongCredentialsStayIndistinguishable() {
        int n = COUNTER.incrementAndGet();
        String username = "admin-" + n + "-" + System.nanoTime();
        seedUser(username, "admin" + n + "-" + System.nanoTime() + "@marel.local");

        // Both refusals carry the same words, so watching the wording cannot tell a stranger
        // which usernames exist.
        assertThatThrownBy(() -> authService.login(username, "not-the-password", "127.0.0.1", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid username or password");

        assertThatThrownBy(() -> authService.login("nobody-" + System.nanoTime(), PASSWORD, "127.0.0.1", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid username or password");
    }

    private boolean signIn(String identifier) {
        return authService.login(identifier, PASSWORD, "127.0.0.1", "test").getAccessToken() != null;
    }

    private void seedUser(String username, String email) {
        Role role = roleRepository.findAll().stream().findFirst().orElseThrow();
        userRepository.saveAndFlush(User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .firstName("Test")
                .lastName("Admin")
                .emailAddress(email)
                .role(role)
                .accountStatus(UserAccountStatus.ACTIVE)
                .active(true)
                .build());
    }
}
