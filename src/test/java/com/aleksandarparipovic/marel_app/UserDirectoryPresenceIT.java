package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.UserService;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
import com.aleksandarparipovic.marel_app.user_preferences.UserPreferences;
import com.aleksandarparipovic.marel_app.user_preferences.UserPreferencesRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user_session.UserSession;
import com.aleksandarparipovic.marel_app.user_session.UserSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the directory adds to a plain user row: the picture somebody chose, and
 * whether they are here right now.
 *
 * <p><b>Presence is derived, never stored.</b> A live session whose heartbeat is
 * inside the threshold counts; anything else does not, and there is deliberately
 * no `users.is_online` — a client that crashes never clears a flag, and the flag
 * becomes a permanent lie about somebody who went home. These assertions are
 * what stop that flag from being reintroduced as an optimisation.
 */
@Transactional
class UserDirectoryPresenceIT extends AbstractIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserPreferencesRepository preferencesRepository;
    @Autowired private UserSessionRepository sessionRepository;

    private static final ObjectMapper JSON = new ObjectMapper();

    private User anAccount() {
        return userRepository.findAll().stream()
                .filter(u -> u.getAccountStatus() == UserAccountStatus.ACTIVE)
                .findFirst().orElseThrow();
    }

    private UserDto rowFor(Long userId) {
        return userService
                .getUsers(0, 200, null, null, null, null, null, Sort.Direction.ASC, "id")
                .getContent().stream()
                .filter(row -> row.getId().equals(userId))
                .findFirst().orElseThrow();
    }

    private void giveSession(User user, OffsetDateTime lastSeen, OffsetDateTime expires) {
        sessionRepository.save(UserSession.builder()
                .user(user)
                .familyId("fam-" + System.nanoTime())
                .lastSeenAt(lastSeen)
                .expiresAt(expires)
                .build());
    }

    // ── Presence ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a session that beat a moment ago means the person is here")
    void recentHeartbeatIsOnline() {
        User user = anAccount();
        OffsetDateTime now = OffsetDateTime.now();
        giveSession(user, now.minusSeconds(5), now.plusDays(1));

        assertThat(rowFor(user.getId()).getOnline()).isTrue();
    }

    /*
     * The whole reason presence is derived. This session was never logged out —
     * the client simply stopped, which is what a crash, a closed laptop and a
     * lost network all look like from here.
     */
    @Test
    @DisplayName("a session that stopped beating goes quiet on its own")
    void staleHeartbeatIsOffline() {
        User user = anAccount();
        OffsetDateTime now = OffsetDateTime.now();
        giveSession(user, now.minusHours(3), now.plusDays(1));

        assertThat(rowFor(user.getId()).getOnline()).isFalse();
    }

    @Test
    @DisplayName("an expired session does not count, however recently it beat")
    void expiredSessionIsOffline() {
        User user = anAccount();
        OffsetDateTime now = OffsetDateTime.now();
        giveSession(user, now.minusSeconds(5), now.minusMinutes(1));

        assertThat(rowFor(user.getId()).getOnline()).isFalse();
    }

    @Test
    @DisplayName("somebody with no session at all is simply not here")
    void noSessionIsOffline() {
        User user = anAccount();

        assertThat(rowFor(user.getId()).getOnline()).isFalse();
    }

    // ── The chosen picture ──────────────────────────────────────────────────

    @Test
    @DisplayName("the directory carries the picture somebody chose for themselves")
    void avatarComesFromTheirOwnPreferences() throws Exception {
        User user = anAccount();
        // @MapsId derives the id FROM the association, so setting userId alone
        // leaves Hibernate with nothing to take it from.
        preferencesRepository.save(UserPreferences.builder()
                .user(user)
                .uiSettings(JSON.readTree("{\"avatarKey\":\"av-07\",\"somethingElse\":42}"))
                .build());

        assertThat(rowFor(user.getId()).getAvatarKey()).isEqualTo("av-07");
    }

    /*
     * Null, not an empty string: the screen chooses between a picture and the
     * person's initials, and "" is neither.
     */
    @Test
    @DisplayName("no chosen picture is null, so the initials are drawn")
    void noAvatarIsNull() {
        User user = anAccount();

        assertThat(rowFor(user.getId()).getAvatarKey()).isNull();
    }
}
