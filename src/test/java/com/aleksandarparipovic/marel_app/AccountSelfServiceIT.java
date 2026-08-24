package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.account.AccountMailer;
import com.aleksandarparipovic.marel_app.account.AccountService;
import com.aleksandarparipovic.marel_app.account.EmailChangeService;
import com.aleksandarparipovic.marel_app.account.dto.PasswordChangeRequest;
import com.aleksandarparipovic.marel_app.account.dto.ProfileUpdateRequest;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user.UserService;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
import com.aleksandarparipovic.marel_app.user.dto.UserUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A person looking after their own account.
 *
 * <p>Two halves. The easy one is contact details: a name and a telephone number
 * are the person's own business and now change without an administrator. The one
 * with teeth is the sign-in address, where every step exists to stop a live
 * session on an unattended machine from being turned into a stolen account.
 */
@Transactional
@Import(AccountSelfServiceIT.MailSpy.class)
class AccountSelfServiceIT extends AbstractIntegrationTest {

    /**
     * Catches the mails the services publish.
     *
     * <p>A plain {@code @EventListener}, not the transactional one the real
     * {@link AccountMailer} uses: the real one deliberately waits for commit, and
     * these tests roll back and never commit. Publishing still reaches this one
     * immediately — which is also the only way a test can learn the code, since it
     * is stored hashed and cannot be read back.
     */
    static class MailSpy {
        final List<AccountMailer.CodeIssued> codes = new ArrayList<>();
        final List<AccountMailer.ChangeCompleted> notices = new ArrayList<>();

        @EventListener
        void onCode(AccountMailer.CodeIssued event) {
            codes.add(event);
        }

        @EventListener
        void onCompleted(AccountMailer.ChangeCompleted event) {
            notices.add(event);
        }

        void clear() {
            codes.clear();
            notices.clear();
        }
    }

    @Autowired private AccountService accountService;
    @Autowired private EmailChangeService emailChangeService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.aleksandarparipovic.marel_app.role.RoleRepository roleRepository;
    @Autowired private MailSpy mail;

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String PASSWORD = "Fabrika2026";

    @BeforeEach
    void clearMail() {
        mail.clear();
    }

    private User anAccount() {
        int n = COUNTER.incrementAndGet();
        // Whatever roles this database was seeded with — the tests here are about
        // account self-service, and none of them turns on which role it is.
        String role = roleRepository.findAll().getFirst().getRoleName();
        UserDto created = userService.create(
                "radnik" + n, PASSWORD, "radnik" + n + "@marel.rs",
                "Petar", "Petrović" + n, null, role);
        return userRepository.findById(created.getId()).orElseThrow();
    }

    /** An account provisioned through Google: verified identity, no local password. */
    private User aGoogleAccount() {
        User user = anAccount();
        user.setPasswordHash(null);
        return userRepository.saveAndFlush(user);
    }

    // ── Contact details ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a person changes their own name and telephone")
    void updatesOwnProfile() {
        User user = anAccount();

        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setFirstName("Petar");
        request.setLastName("Novaković");
        request.setMobilePhone(" 064 111 2233 ");
        request.setDisplayName("Pera");

        UserDto updated = accountService.updateOwnProfile(user.getId(), request);

        assertThat(updated.getLastName()).isEqualTo("Novaković");
        assertThat(updated.getMobilePhone()).isEqualTo("064 111 2233");
        assertThat(updated.getDisplayName()).isEqualTo("Pera");
        // full_name is generated by the database and must follow the parts.
        assertThat(updated.getFullName()).isEqualTo("Petar Novaković");
    }

    @Test
    @DisplayName("having no telephone is an answer; having no name is not")
    void blankHandling() {
        User user = anAccount();

        ProfileUpdateRequest clearPhone = new ProfileUpdateRequest();
        clearPhone.setMobilePhone("   ");
        assertThat(accountService.updateOwnProfile(user.getId(), clearPhone).getMobilePhone()).isNull();

        // first_name is NOT NULL and feeds full_name, which some forty queries
        // order by. An empty one would make somebody vanish from every list.
        ProfileUpdateRequest blankName = new ProfileUpdateRequest();
        blankName.setFirstName("  ");
        assertThatThrownBy(() -> accountService.updateOwnProfile(user.getId(), blankName))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /*
     * The fields NOT on ProfileUpdateRequest are the point of it. Nothing a person
     * sends to their own profile may reach the address they sign in with, the
     * username, or the role.
     */
    @Test
    @DisplayName("self-service cannot touch a credential or a role")
    void profileUpdateCarriesNoCredentials() {
        List<String> settable = java.util.Arrays.stream(ProfileUpdateRequest.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .filter(name -> !name.startsWith("$"))
                .toList();

        assertThat(settable)
                .containsExactlyInAnyOrder("firstName", "lastName", "mobilePhone", "displayName");
    }

    // ── Password ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a person changes their own password")
    void changesOwnPassword() {
        User user = anAccount();

        accountService.changeOwnPassword(user.getId(), passwordChange(PASSWORD, "Nova2026Lozinka"));

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("Nova2026Lozinka", reloaded.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, reloaded.getPasswordHash())).isFalse();
    }

    /*
     * A session is "this machine was signed in at some point" — which is exactly
     * the state of an unattended terminal on a shop floor. Knowing the password is
     * the only evidence that the person at the keyboard owns the account.
     */
    @Test
    @DisplayName("the current password is required, session or no session")
    void refusesWithoutTheCurrentPassword() {
        User user = anAccount();

        assertThatThrownBy(() ->
                accountService.changeOwnPassword(user.getId(), passwordChange("pogresna", "Nova2026Lozinka")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Trenutna lozinka");
    }

    @Test
    @DisplayName("the new password is typed twice, and the two must agree")
    void refusesMismatchedConfirmation() {
        User user = anAccount();

        PasswordChangeRequest request = passwordChange(PASSWORD, "Nova2026Lozinka");
        request.setConfirmPassword("Nova2026Lozinkaa");

        assertThatThrownBy(() -> accountService.changeOwnPassword(user.getId(), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne poklapaju");
    }

    @Test
    @DisplayName("the policy applies to a change, not only to registration")
    void appliesThePolicy() {
        User user = anAccount();

        assertThatThrownBy(() ->
                accountService.changeOwnPassword(user.getId(), passwordChange(PASSWORD, "kratka")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("najmanje");
    }

    @Test
    @DisplayName("the new password has to be a different one")
    void refusesTheSamePassword() {
        User user = anAccount();

        assertThatThrownBy(() ->
                accountService.changeOwnPassword(user.getId(), passwordChange(PASSWORD, PASSWORD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("različita");
    }

    /*
     * Setting one here would quietly open a SECOND way into an account whose owner
     * believes it is reachable only through Google.
     */
    @Test
    @DisplayName("a Google account has no password to change")
    void googleAccountHasNoPassword() {
        User user = aGoogleAccount();

        assertThatThrownBy(() ->
                accountService.changeOwnPassword(user.getId(), passwordChange(PASSWORD, "Nova2026Lozinka")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Google");
    }

    // ── The username never moves ────────────────────────────────────────────

    @Test
    @DisplayName("not even an administrator may change a username")
    void usernameIsFrozen() {
        User user = anAccount();

        UserUpdateRequest rename = new UserUpdateRequest();
        rename.setUsername("nesto.drugo");

        // Refused out loud rather than ignored: an administrator who types a new
        // username and watches nothing happen will simply try again.
        assertThatThrownBy(() -> userService.update(user.getId(), rename))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne može promeniti");
    }

    private PasswordChangeRequest passwordChange(String current, String next) {
        PasswordChangeRequest request = new PasswordChangeRequest();
        request.setCurrentPassword(current);
        request.setNewPassword(next);
        request.setConfirmPassword(next);
        return request;
    }

    // ── The address ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the whole exchange: password, code to the new box, then the change")
    void completesTheExchange() {
        User user = anAccount();
        String oldEmail = user.getEmailAddress();
        String newEmail = "nova" + user.getId() + "@marel.rs";

        emailChangeService.start(user.getId(), newEmail, PASSWORD);

        // NOTHING has moved yet. The old address still signs in.
        assertThat(userRepository.findById(user.getId()).orElseThrow().getEmailAddress())
                .isEqualTo(oldEmail);

        // The code went to the NEW box — the only place it is of any use to
        // somebody who genuinely owns that mailbox.
        assertThat(mail.codes).hasSize(1);
        assertThat(mail.codes.getFirst().toAddress()).isEqualTo(newEmail);
        String code = mail.codes.getFirst().code();
        assertThat(code).hasSize(6).containsOnlyDigits();

        emailChangeService.confirm(user.getId(), code, "session-1");

        assertThat(userRepository.findById(user.getId()).orElseThrow().getEmailAddress())
                .isEqualTo(newEmail);

        // And the OLD box is told. The new one already knows — it just confirmed;
        // this is the only warning the rightful owner gets if it was not them.
        assertThat(mail.notices).hasSize(1);
        assertThat(mail.notices.getFirst().oldAddress()).isEqualTo(oldEmail);
        assertThat(mail.notices.getFirst().newAddress()).isEqualTo(newEmail);
    }

    @Test
    @DisplayName("a wrong code changes nothing and is counted")
    void wrongCodeIsCounted() {
        User user = anAccount();
        String oldEmail = user.getEmailAddress();
        emailChangeService.start(user.getId(), "nova" + user.getId() + "@marel.rs", PASSWORD);

        assertThatThrownBy(() -> emailChangeService.confirm(user.getId(), "000000", "session-1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(userRepository.findById(user.getId()).orElseThrow().getEmailAddress())
                .isEqualTo(oldEmail);
        assertThat(emailChangeService.pending(user.getId()).orElseThrow().attemptsLeft()).isEqualTo(4);
    }

    /*
     * Six digits is a million possibilities — nothing to a machine. The counter is
     * what makes it one guess at a time instead of a million.
     */
    @Test
    @DisplayName("guessing runs out, and the right code no longer helps")
    void guessingRunsOut() {
        User user = anAccount();
        emailChangeService.start(user.getId(), "nova" + user.getId() + "@marel.rs", PASSWORD);
        String realCode = mail.codes.getFirst().code();

        for (int i = 0; i < 5; i++) {
            String guess = String.format("%06d", 999_990 + i);
            assertThatThrownBy(() -> emailChangeService.confirm(user.getId(), guess, "s"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThatThrownBy(() -> emailChangeService.confirm(user.getId(), realCode, "s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Previše pogrešnih pokušaja");
    }

    @Test
    @DisplayName("the password is required to start, and a wrong one sends no code")
    void startNeedsThePassword() {
        User user = anAccount();

        assertThatThrownBy(() ->
                emailChangeService.start(user.getId(), "nova@marel.rs", "pogresna"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(mail.codes).isEmpty();
        assertThat(emailChangeService.pending(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("a Google account cannot change its address here at all")
    void googleAccountCannotChangeAddress() {
        User user = aGoogleAccount();

        assertThatThrownBy(() ->
                emailChangeService.start(user.getId(), "nova@marel.rs", PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Google");
        assertThat(mail.codes).isEmpty();
    }

    @Test
    @DisplayName("an address somebody else already uses is refused, without naming them")
    void refusesATakenAddress() {
        User user = anAccount();
        User other = anAccount();

        assertThatThrownBy(() ->
                emailChangeService.start(user.getId(), other.getEmailAddress(), PASSWORD))
                .isInstanceOf(ConflictException.class)
                // Saying WHOSE it is would turn this into a way to look up who
                // holds an address.
                .hasMessageNotContaining(other.getUsername());
    }

    /*
     * Somebody who mistyped the address expects to start again immediately.
     * Superseding also invalidates the code already in the first mailbox — two
     * live codes would mean the account's destination depends on which mail
     * happens to be read first.
     */
    @Test
    @DisplayName("starting again replaces the first request, and its code stops working")
    void startingAgainSupersedes() {
        User user = anAccount();
        emailChangeService.start(user.getId(), "prva" + user.getId() + "@marel.rs", PASSWORD);
        String firstCode = mail.codes.getFirst().code();

        emailChangeService.start(user.getId(), "druga" + user.getId() + "@marel.rs", PASSWORD);

        assertThat(emailChangeService.pending(user.getId()).orElseThrow().newEmail())
                .isEqualTo("druga" + user.getId() + "@marel.rs");

        assertThatThrownBy(() -> emailChangeService.confirm(user.getId(), firstCode, "s"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cancelling puts the code out of use")
    void cancelling() {
        User user = anAccount();
        emailChangeService.start(user.getId(), "nova" + user.getId() + "@marel.rs", PASSWORD);
        String code = mail.codes.getFirst().code();

        emailChangeService.cancel(user.getId());

        assertThat(emailChangeService.pending(user.getId())).isEmpty();
        assertThatThrownBy(() -> emailChangeService.confirm(user.getId(), code, "s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nema započete promene");
    }

    @Test
    @DisplayName("the pending state never carries the code")
    void pendingHidesTheCode() {
        User user = anAccount();
        emailChangeService.start(user.getId(), "nova" + user.getId() + "@marel.rs", PASSWORD);
        String code = mail.codes.getFirst().code();

        // An endpoint that returned it would make the whole exchange pointless:
        // the point is proving you can read the mailbox.
        assertThat(emailChangeService.pending(user.getId()).orElseThrow().toString())
                .doesNotContain(code);
    }
}
