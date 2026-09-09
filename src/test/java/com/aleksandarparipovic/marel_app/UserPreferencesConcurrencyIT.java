package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user_preferences.UserPreferencesService;
import com.aleksandarparipovic.marel_app.user_preferences.UserTheme;
import com.aleksandarparipovic.marel_app.user_preferences.dto.UserPreferencesUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately NOT {@code @Transactional}: each service call needs its own real
 * transaction that actually commits, because what is under test is exactly what
 * happens when two of them commit against the same row.
 *
 * <p>A user's preferences are written from several places on the client — the
 * theme sync, the profile page, the sidebar toggle — and two firing close
 * together used to race the {@code @Version} column: the second flush matched no
 * row and threw {@link org.springframework.orm.ObjectOptimisticLockingFailureException},
 * so a theme toggle would visibly snap back. The update path now locks the row,
 * serialising those overlapping writes.
 */
class UserPreferencesConcurrencyIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private UserPreferencesService service;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @Test
    @DisplayName("two of one user's preference writes racing both land, instead of the second one failing")
    void concurrentUpdatesBothSucceed() throws Exception {
        Long userId = newUser().getId();
        // Make the row exist first, so the race is purely on the version column.
        service.update(userId, themeRequest(UserTheme.SYSTEM));

        int rounds = 25;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < rounds; i++) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                UserTheme first = (i % 2 == 0) ? UserTheme.DARK : UserTheme.LIGHT;
                UserTheme second = (i % 2 == 0) ? UserTheme.LIGHT : UserTheme.DARK;
                Future<?> a = pool.submit(() -> runUpdate(barrier, userId, first, failures));
                Future<?> b = pool.submit(() -> runUpdate(barrier, userId, second, failures));
                a.get(15, TimeUnit.SECONDS);
                b.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures)
                .as("no update was rejected by an optimistic-lock race")
                .isEmpty();
        // The row still reads back, holding whichever of the two wrote last.
        assertThat(service.get(userId).theme()).isIn(UserTheme.DARK, UserTheme.LIGHT);
    }

    private void runUpdate(CyclicBarrier barrier, Long userId, UserTheme theme, List<Throwable> failures) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
            service.update(userId, themeRequest(theme));
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    private static UserPreferencesUpdateRequest themeRequest(UserTheme theme) {
        UserPreferencesUpdateRequest request = new UserPreferencesUpdateRequest();
        request.setTheme(theme);
        return request;
    }

    private User newUser() {
        int n = COUNTER.incrementAndGet();
        Role role = roleRepository.findAll().stream().findFirst().orElseThrow();
        return userRepository.save(User.builder()
                .username("pref-" + n + "-" + System.nanoTime())
                .passwordHash("x")
                .firstName("Test")
                .lastName("Pref" + n)
                .emailAddress("pref" + n + "-" + System.nanoTime() + "@example.rs")
                .role(role)
                .accountStatus(UserAccountStatus.ACTIVE)
                .active(true)
                .build());
    }
}
