package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.common.WrongPasswordException;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderService;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderUpdateRequest;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrderService;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrderStatus;
import com.aleksandarparipovic.marel_app.sample_order.repository.SampleOrderRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Calling an order off.
 *
 * <p>The record this protects: a cancelled order is terminal (nothing may edit
 * it, deliver it or close it afterwards), the cancellation is signed (wrong
 * password touches nothing), and the signature — who, when — is written on the
 * order itself.
 */
@Transactional
class OrderCancelIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Tacna-lozinka-1";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private ProductionOrderService productionOrderService;
    @Autowired private ProductionOrderRepository productionOrderRepository;
    @Autowired private SampleOrderService sampleOrderService;
    @Autowired private SampleOrderRepository sampleOrderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /** A signed-in commercial whose real password hash is in the database. */
    private Authentication asCommercial() {
        int n = COUNTER.incrementAndGet();
        Role role = roleRepository.findAll().stream().findFirst().orElseThrow();
        User user = userRepository.save(User.builder()
                .username("otkaz-" + n + "-" + System.nanoTime())
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .firstName("Otkaz")
                .lastName("Test" + n)
                .emailAddress("otkaz" + n + "-" + System.nanoTime() + "@example.rs")
                .role(role)
                .accountStatus(UserAccountStatus.ACTIVE)
                .active(true)
                .build());

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.getUsername(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_commercial")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }

    private ProductionOrder anOrder(ProductionOrderStatus status) {
        int n = COUNTER.incrementAndGet();
        return productionOrderRepository.save(ProductionOrder.builder()
                .code("OTKAZ-" + n + "-" + System.nanoTime())
                .name("Nalog za otkaz " + n)
                .status(status)
                .testingRequired(false)
                .isHighPriority(false)
                .isAnnounced(false)
                .hasSuccessiveDeliveries(false)
                .isActive(true)
                .build());
    }

    private SampleOrder aSampleOrder(String status) {
        int n = COUNTER.incrementAndGet();
        return sampleOrderRepository.save(SampleOrder.builder()
                .code("UZ-OTKAZ-" + n + "-" + System.nanoTime())
                .name("Uzorci za otkaz " + n)
                .creationDate(LocalDate.now())
                .deadlineDate(LocalDate.now().plusDays(7))
                .status(status)
                .isActive(true)
                .build());
    }

    @Test
    @DisplayName("a cancelled production order is terminal, and the signature is on it")
    void productionOrderCancelIsTerminal() {
        Authentication auth = asCommercial();
        ProductionOrder order = anOrder(ProductionOrderStatus.CREATED);

        productionOrderService.cancel(order.getId(), PASSWORD, auth);

        ProductionOrder cancelled = productionOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ProductionOrderStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getCancelledBy()).isNotNull();

        ProductionOrderUpdateRequest edit = new ProductionOrderUpdateRequest(
                "Novo ime", null, null, false,
                null, null, null, false, false, false, null, null);
        assertThatThrownBy(() -> productionOrderService.update(order.getId(), edit))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> productionOrderService.markDelivered(order.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a wrong password touches nothing")
    void wrongPasswordTouchesNothing() {
        Authentication auth = asCommercial();
        ProductionOrder order = anOrder(ProductionOrderStatus.CREATED);

        assertThatThrownBy(() -> productionOrderService.cancel(order.getId(), "pogresna", auth))
                .isInstanceOf(WrongPasswordException.class);

        assertThat(productionOrderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ProductionOrderStatus.CREATED);
    }

    @Test
    @DisplayName("a delivered order cannot be called off — it happened")
    void deliveredOrderCannotBeCancelled() {
        Authentication auth = asCommercial();
        ProductionOrder order = anOrder(ProductionOrderStatus.DELIVERED);

        assertThatThrownBy(() -> productionOrderService.cancel(order.getId(), PASSWORD, auth))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("re-cancelling is a no-op, not an error")
    void reCancelIsNoOp() {
        Authentication auth = asCommercial();
        ProductionOrder order = anOrder(ProductionOrderStatus.CREATED);

        productionOrderService.cancel(order.getId(), PASSWORD, auth);
        productionOrderService.cancel(order.getId(), PASSWORD, auth);

        assertThat(productionOrderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(ProductionOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("a cancelled sample order is terminal too, and a closed one stays closed")
    void sampleOrderCancelIsTerminal() {
        Authentication auth = asCommercial();
        SampleOrder order = aSampleOrder(SampleOrderStatus.CREATED);

        sampleOrderService.cancel(order.getId(), PASSWORD, auth);

        SampleOrder cancelled = sampleOrderRepository.findById(order.getId()).orElseThrow();
        assertThat(SampleOrderStatus.isCancelled(cancelled.getStatus())).isTrue();
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getCancelledBy()).isNotNull();

        assertThatThrownBy(() -> sampleOrderService.close(order.getId()))
                .isInstanceOf(ConflictException.class);

        SampleOrder closed = aSampleOrder(SampleOrderStatus.CLOSED);
        assertThatThrownBy(() -> sampleOrderService.cancel(closed.getId(), PASSWORD, auth))
                .isInstanceOf(ConflictException.class);
    }
}
