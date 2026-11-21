package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.account.AccountMailer;
import com.aleksandarparipovic.marel_app.account.GoogleAccountLinkService;
import com.aleksandarparipovic.marel_app.account.dto.GoogleLinkStatusResponse;
import com.aleksandarparipovic.marel_app.auth.AuthService;
import com.aleksandarparipovic.marel_app.auth.google.GoogleLinkCodeStore;
import com.aleksandarparipovic.marel_app.auth.google.GoogleUserInfo;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user.UserService;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A profile joining itself to a Google account.
 *
 * <p>Before this existed, "Nastavi preko Google-a" resolved a profile by
 * comparing the Google-verified address with {@code users.email_address} and
 * nothing else — so the feature worked for whoever happened to be registered on
 * their Gmail and for nobody else. The tests here are about the two things that
 * had to be true for the join to be safe: that one Google account can never
 * answer for two profiles, and that the last step belongs to the profile's own
 * session rather than to the browser redirect.
 */
@Transactional
@Import(GoogleAccountLinkIT.MailSpy.class)
/*
 * CREDENTIALS, unlike the rest of the suite. The shared test profile leaves
 * google.oauth.* empty, and an unconfigured server refuses to begin a join at
 * all — which would hide every rule below the first check. These are placeholder
 * values and no request ever reaches Google: the round-trip is stood in for by
 * issuing the link code directly, which is exactly what the callback does.
 */
@TestPropertySource(properties = {
        "google.oauth.client-id=test-client-id",
        "google.oauth.client-secret=test-client-secret",
})
class GoogleAccountLinkIT extends AbstractIntegrationTest {

    /** Plain {@code @EventListener}s: these tests roll back and never commit. */
    static class MailSpy {
        final List<AccountMailer.GoogleLinked> linked = new ArrayList<>();
        final List<AccountMailer.GoogleUnlinked> unlinked = new ArrayList<>();

        @EventListener
        void onLinked(AccountMailer.GoogleLinked event) {
            linked.add(event);
        }

        @EventListener
        void onUnlinked(AccountMailer.GoogleUnlinked event) {
            unlinked.add(event);
        }

        void clear() {
            linked.clear();
            unlinked.clear();
        }
    }

    @Autowired private GoogleAccountLinkService linkService;
    @Autowired private GoogleLinkCodeStore linkCodeStore;
    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private com.aleksandarparipovic.marel_app.role.RoleRepository roleRepository;
    @Autowired private MailSpy mail;

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String PASSWORD = "Fabrika2026";

    @BeforeEach
    void clearMail() {
        mail.clear();
    }

    /** An ordinary account: registered with an address and a password, no Google. */
    private User anAccount() {
        int n = COUNTER.incrementAndGet();
        String role = roleRepository.findAll().getFirst().getRoleName();
        UserDto created = userService.create(
                "radnik" + n, PASSWORD, "radnik" + n + "@marel.rs",
                "Petar", "Petrović" + n, null, role);
        return userRepository.findById(created.getId()).orElseThrow();
    }

    /** The code the Google round-trip would have handed back for this profile. */
    private String aCodeFor(User user, String subject, String googleEmail) {
        return linkCodeStore.issue(new GoogleLinkCodeStore.PendingLink(
                user.getId(), subject, googleEmail));
    }

    private GoogleUserInfo googleSignIn(String subject, String email) {
        return new GoogleUserInfo(subject, email, true, "Petar", "Petrović");
    }

    // ── Joining ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a profile registered on a company address joins its own Gmail")
    void joinsAGmail() {
        User user = anAccount();

        assertThat(linkService.status(user.getId()).linked()).isFalse();

        GoogleLinkStatusResponse status = linkService.completeLink(
                user.getId(), aCodeFor(user, "108-sub", "pera.petrovic@gmail.com"));

        assertThat(status.linked()).isTrue();
        assertThat(status.googleEmail()).isEqualTo("pera.petrovic@gmail.com");
        assertThat(status.linkedAt()).isNotNull();

        // The account's OWN address is untouched — the whole point is that the two
        // are allowed to differ.
        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getEmailAddress()).isEqualTo(user.getEmailAddress());
        assertThat(saved.getGoogleSubject()).isEqualTo("108-sub");

        // The letter goes to the ACCOUNT address: it is the owner's warning if
        // somebody else added a way in at an unlocked session.
        assertThat(mail.linked).singleElement().satisfies(event -> {
            assertThat(event.toAddress()).isEqualTo(user.getEmailAddress());
            assertThat(event.googleEmail()).isEqualTo("pera.petrovic@gmail.com");
        });
    }

    @Test
    @DisplayName("signing in with Google then finds the profile by subject, not by address")
    void signInResolvesBySubject() {
        User user = anAccount();
        linkService.completeLink(user.getId(), aCodeFor(user, "108-sub", "pera@gmail.com"));

        // The SAME Google account, after its owner renamed the mailbox. The address
        // now matches nothing in the database; the subject still matches.
        var found = authService.findGoogleUserForLogin(googleSignIn("108-sub", "petar.novi@gmail.com"));

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("without any join, Google sign-in still matches the account address")
    void signInFallsBackToTheAccountAddress() {
        User user = anAccount();

        var found = authService.findGoogleUserForLogin(
                googleSignIn("777-sub", user.getEmailAddress()));

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());

        // And nothing was recorded off the back of it: a join is a statement its
        // owner makes, not one the application infers from a matching address.
        assertThat(userRepository.findById(user.getId()).orElseThrow().getGoogleSubject()).isNull();
    }

    // ── One Google account, one profile ─────────────────────────────────────

    @Test
    @DisplayName("a Google account already joined elsewhere is refused")
    void refusesAGoogleAccountJoinedElsewhere() {
        User first = anAccount();
        User second = anAccount();

        linkService.completeLink(first.getId(), aCodeFor(first, "108-sub", "zajednicki@gmail.com"));

        String code = aCodeFor(second, "108-sub", "zajednicki@gmail.com");
        assertThatThrownBy(() -> linkService.completeLink(second.getId(), code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("već povezan sa drugim profilom");

        assertThat(linkService.status(second.getId()).linked()).isFalse();
    }

    @Test
    @DisplayName("a Gmail that is another profile's own account address is refused")
    void refusesAnotherProfilesAccountAddress() {
        User mine = anAccount();
        User colleague = anAccount();

        // That colleague signs in with Google today through the fallback
        // comparison; joining their address here would make two profiles answer
        // for one Google account.
        String code = aCodeFor(mine, "999-sub", colleague.getEmailAddress());

        assertThatThrownBy(() -> linkService.completeLink(mine.getId(), code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adresa drugog naloga");
    }

    @Test
    @DisplayName("a second join is refused while one is in place")
    void refusesASecondJoin() {
        User user = anAccount();
        linkService.completeLink(user.getId(), aCodeFor(user, "108-sub", "prvi@gmail.com"));

        String code = aCodeFor(user, "222-sub", "drugi@gmail.com");
        assertThatThrownBy(() -> linkService.completeLink(user.getId(), code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("već povezan");

        // Severing is the only way to move the link, and severing asks for the
        // password — an overwrite would have been a severance without the proof.
        // Refused at the START of the round-trip, so nobody is sent to Google
        // only to be turned away on the way back.
        assertThatThrownBy(() -> linkService.startLink(user.getId(), "web"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prekinite postojeću vezu");
    }

    // ── The code belongs to one profile ─────────────────────────────────────

    @Test
    @DisplayName("a code issued for one profile cannot be spent by another")
    void refusesACodeIssuedForSomebodyElse() {
        User victim = anAccount();
        User other = anAccount();

        String code = aCodeFor(victim, "108-sub", "pera@gmail.com");

        assertThatThrownBy(() -> linkService.completeLink(other.getId(), code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne pripada ovom nalogu");

        assertThat(linkService.status(other.getId()).linked()).isFalse();
        assertThat(linkService.status(victim.getId()).linked()).isFalse();
    }

    @Test
    @DisplayName("a code is single-use")
    void codeIsSpentOnce() {
        User user = anAccount();
        String code = aCodeFor(user, "108-sub", "pera@gmail.com");

        linkService.completeLink(user.getId(), code);
        linkService.unlink(user.getId(), PASSWORD);

        assertThatThrownBy(() -> linkService.completeLink(user.getId(), code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("istekao ili je nevažeći");
    }

    @Test
    @DisplayName("a Google identity with no subject is not recorded")
    void refusesAnIdentityWithoutASubject() {
        User user = anAccount();
        String code = aCodeFor(user, null, "pera@gmail.com");

        assertThatThrownBy(() -> linkService.completeLink(user.getId(), code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identifikator");
    }

    // ── Severing ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("severing asks for the password and then clears the join")
    void severs() {
        User user = anAccount();
        linkService.completeLink(user.getId(), aCodeFor(user, "108-sub", "pera@gmail.com"));

        assertThatThrownBy(() -> linkService.unlink(user.getId(), "pogresna-lozinka"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(linkService.status(user.getId()).linked()).isTrue();

        GoogleLinkStatusResponse after = linkService.unlink(user.getId(), PASSWORD);

        assertThat(after.linked()).isFalse();
        assertThat(after.googleEmail()).isNull();

        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getGoogleSubject()).isNull();
        assertThat(saved.getGoogleLinkedAt()).isNull();

        assertThat(mail.unlinked).singleElement().satisfies(event ->
                assertThat(event.googleEmail()).isEqualTo("pera@gmail.com"));

        // And the Google account is free again, for this profile or another.
        assertThat(authService.findGoogleUserForLogin(googleSignIn("108-sub", "pera@gmail.com")))
                .isEmpty();
    }

    @Test
    @DisplayName("an account with no password cannot sever its only way in")
    void refusesSeveringWithoutALocalPassword() {
        User user = anAccount();
        linkService.completeLink(user.getId(), aCodeFor(user, "108-sub", "pera@gmail.com"));

        // A legacy Google-provisioned account: verified identity, no local password.
        User passwordless = userRepository.findById(user.getId()).orElseThrow();
        passwordless.setPasswordHash(null);
        userRepository.saveAndFlush(passwordless);

        assertThatThrownBy(() -> linkService.unlink(user.getId(), PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prvo postavite lozinku");

        assertThat(linkService.status(user.getId()).linked()).isTrue();
        assertThat(linkService.status(user.getId()).hasPassword()).isFalse();
    }

    @Test
    @DisplayName("a join begins with a Google URL carrying a single-use state")
    void startsWithAnAuthorizationUrl() {
        User user = anAccount();

        String url = linkService.startLink(user.getId(), "web").authorizationUrl();

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
        // The state is the whole protection: it is what ties the round-trip to
        // this profile, and it is issued here rather than chosen by the client.
        assertThat(url).contains("state=");
        assertThat(url).contains("prompt=select_account");
    }

    @Test
    @DisplayName("severing a profile that has no join is refused")
    void refusesSeveringNothing() {
        User user = anAccount();

        assertThatThrownBy(() -> linkService.unlink(user.getId(), PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nije povezan");
    }
}
