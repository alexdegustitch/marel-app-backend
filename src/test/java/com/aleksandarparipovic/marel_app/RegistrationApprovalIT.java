package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.auth.AuthService;
import com.aleksandarparipovic.marel_app.auth.dto.RegisterRequest;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user_registration_request.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class RegistrationApprovalIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRegistrationRequestService requestService;
    @Autowired private UserRegistrationRequestRepository requestRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private RegisterRequest newRegistration() {
        int n = COUNTER.incrementAndGet();
        Role role = roleRepository.findAll().stream()
                .filter(r -> !"developer".equalsIgnoreCase(r.getRoleName()))
                .findFirst().orElseThrow();

        RegisterRequest request = new RegisterRequest();
        request.setFirstName("Test");
        request.setLastName("User" + n);
        request.setEmailAddress("test.user" + n + "@example.rs");
        request.setPassword("Test1234");
        request.setConfirmPassword("Test1234");
        request.setRoleId(role.getId());
        return request;
    }

    private User anAdmin() {
        return userRepository.findAll().stream()
                .filter(u -> u.getAccountStatus() == UserAccountStatus.ACTIVE)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("registration creates a pending user and exactly one pending request")
    void registrationIsAtomic() {
        var response = authService.register(newRegistration());

        User user = userRepository.findById(response.userId()).orElseThrow();
        assertThat(user.getAccountStatus()).isEqualTo(UserAccountStatus.PENDING_APPROVAL);
        // is_active is derived by the database trigger, never set independently.
        assertThat(user.getActive()).isFalse();

        assertThat(requestRepository.existsByUser_IdAndStatus(
                user.getId(), UserRegistrationRequestStatus.PENDING)).isTrue();
    }

    @Test
    @DisplayName("approval activates the account and stamps the reviewer")
    void approvalActivatesUser() {
        var registered = authService.register(newRegistration());
        Long requestId = pendingRequestIdFor(registered.userId());
        User reviewer = anAdmin();

        var reviewed = requestService.approve(requestId, reviewer.getId(), "U redu.");

        assertThat(reviewed.status()).isEqualTo(UserRegistrationRequestStatus.APPROVED);
        assertThat(reviewed.reviewedByUserId()).isEqualTo(reviewer.getId());
        assertThat(reviewed.reviewedAt()).isNotNull();

        User user = userRepository.findById(registered.userId()).orElseThrow();
        assertThat(user.getAccountStatus()).isEqualTo(UserAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("decline marks the account DECLINED without archiving it")
    void declineDoesNotArchive() {
        var registered = authService.register(newRegistration());
        Long requestId = pendingRequestIdFor(registered.userId());

        requestService.decline(requestId, anAdmin().getId(), "Nije potrebno.");

        User user = userRepository.findById(registered.userId()).orElseThrow();
        assertThat(user.getAccountStatus()).isEqualTo(UserAccountStatus.DECLINED);
        // Declining is a refusal, not a retirement: archived_at must stay clear.
        assertThat(user.getArchivedAt()).isNull();
    }

    @Test
    @DisplayName("an already reviewed request cannot be reviewed again")
    void terminalStatusIsTerminal() {
        var registered = authService.register(newRegistration());
        Long requestId = pendingRequestIdFor(registered.userId());
        Long reviewerId = anAdmin().getId();

        requestService.approve(requestId, reviewerId, null);

        assertThatThrownBy(() -> requestService.approve(requestId, reviewerId, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> requestService.decline(requestId, reviewerId, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a user cannot hold two pending registration requests")
    void onlyOnePendingRequestPerUser() {
        var registered = authService.register(newRegistration());
        User user = userRepository.findById(registered.userId()).orElseThrow();

        assertThatThrownBy(() -> requestService.openFor(user))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a pending account cannot obtain tokens")
    void pendingAccountCannotLogIn() {
        RegisterRequest request = newRegistration();
        authService.register(request);

        String username = userRepository.findByEmailAddressIgnoreCase(request.getEmailAddress())
                .orElseThrow().getUsername();

        assertThatThrownBy(() ->
                authService.login(username, request.getPassword(), "127.0.0.1", "test"))
                .isInstanceOf(com.aleksandarparipovic.marel_app.auth.AccountNotUsableException.class)
                .hasMessageContaining("odobrenje");
    }

    private Long pendingRequestIdFor(Long userId) {
        return requestRepository.findAll().stream()
                .filter(r -> r.getUser().getId().equals(userId))
                .filter(r -> r.getStatus() == UserRegistrationRequestStatus.PENDING)
                .findFirst().orElseThrow().getId();
    }
}
