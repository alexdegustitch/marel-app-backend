package com.aleksandarparipovic.marel_app.account;

import com.aleksandarparipovic.marel_app.account.dto.GoogleLinkStartResponse;
import com.aleksandarparipovic.marel_app.account.dto.GoogleLinkStatusResponse;
import com.aleksandarparipovic.marel_app.auth.google.GoogleLinkCodeStore;
import com.aleksandarparipovic.marel_app.auth.google.GoogleOAuthService;
import com.aleksandarparipovic.marel_app.auth.google.OAuthStateStore;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Joining one's own profile to a Google account, and severing it again.
 *
 * <p><b>Why this exists.</b> Google sign-in used to resolve a profile by
 * comparing the Google-verified address with {@code users.email_address} and
 * nothing else. That handed the feature to exactly one group by accident — the
 * people whose ACCOUNT address happens to be their Gmail — and left everybody on
 * a company address with no way to ask for it. The join recorded here is that
 * way to ask.
 *
 * <p><b>What a join is NOT.</b> It is not a second password: every signed action
 * in the application is still confirmed with the LOCAL password. It grants no
 * permission, changes no role, and does not touch the account's own e-mail
 * address — the two addresses are allowed to differ, and differing is the normal
 * case this whole feature is for.
 *
 * <p><b>The round-trip.</b> Three steps, because the middle one happens in a
 * browser outside the application: the signed-in session asks for a URL
 * ({@link #startLink}); Google confirms the identity and the callback hands back
 * a one-time code; the session posts that code back with its token
 * ({@link #completeLink}). The last step is what makes the join an act of the
 * profile's own session rather than of an anonymous redirect.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAccountLinkService {

    private final UserRepository userRepository;
    private final GoogleOAuthService googleOAuthService;
    private final OAuthStateStore oAuthStateStore;
    private final GoogleLinkCodeStore linkCodeStore;
    private final AccountService accountService;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public GoogleLinkStatusResponse status(Long userId) {
        return statusOf(load(userId));
    }

    /**
     * Begin a join: issue the state that binds this round-trip to this profile,
     * and hand back Google's authorization URL.
     *
     * <p>Refused outright when something is ALREADY joined. Silently replacing it
     * would let an unlocked session move the sign-in route to another Google
     * account without ever proving it is the owner — severing asks for the
     * password precisely to stop that, and an overwrite is a severance with extra
     * steps. The screen says to sever first.
     */
    @Transactional(readOnly = true)
    public GoogleLinkStartResponse startLink(Long userId, String clientType) {
        User user = load(userId);

        if (!googleOAuthService.isConfigured()) {
            throw new IllegalStateException(
                    "Prijava preko Google-a nije podešena na serveru. Javite se administratoru.");
        }

        if (user.getGoogleSubject() != null || user.getGoogleEmail() != null) {
            throw new IllegalArgumentException("Nalog je već povezan sa Google nalogom "
                    + user.getGoogleEmail() + ". Prekinite postojeću vezu pa povežite drugi nalog.");
        }

        String state = oAuthStateStore.issue(clientType, OAuthStateStore.INTENT_LINK, user.getId());
        return new GoogleLinkStartResponse(googleOAuthService.buildAuthorizationUrl(state).toString());
    }

    /**
     * Apply the join the Google round-trip prepared.
     *
     * <p>The code is consumed first and then checked against the CALLER: a code
     * belongs to the profile that started the round-trip, so one that leaked or
     * was replayed cannot attach a Google account to anybody else's profile.
     */
    @Transactional
    public GoogleLinkStatusResponse completeLink(Long userId, String code) {
        User user = load(userId);

        GoogleLinkCodeStore.PendingLink pending = linkCodeStore.consume(code)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Kod je istekao ili je nevažeći. Pokušajte povezivanje ponovo."));

        if (!user.getId().equals(pending.userId())) {
            // Not a validation message the caller can act on — it is a refusal.
            log.warn("Google link code issued for user {} was presented by user {}",
                    pending.userId(), userId);
            throw new IllegalArgumentException(
                    "Kod ne pripada ovom nalogu. Pokušajte povezivanje ponovo.");
        }

        if (user.getGoogleSubject() != null || user.getGoogleEmail() != null) {
            throw new IllegalArgumentException(
                    "Nalog je već povezan sa Google nalogom. Prekinite postojeću vezu pa povežite drugi.");
        }

        if (pending.subject() == null || pending.subject().isBlank()) {
            // Without a subject there is nothing stable to record, and recording
            // only the address would produce a link that breaks the day its owner
            // renames the mailbox.
            throw new IllegalArgumentException(
                    "Google nije vratio identifikator naloga. Pokušajte ponovo.");
        }

        String email = pending.email() == null ? null : pending.email().trim();
        if (email == null || email.isEmpty()) {
            throw new IllegalArgumentException("Google nije vratio e-adresu. Pokušajte ponovo.");
        }

        requireGoogleAccountIsFree(user, pending.subject(), email);

        user.setGoogleSubject(pending.subject());
        user.setGoogleEmail(email);
        user.setGoogleLinkedAt(OffsetDateTime.now());
        User saved = userRepository.save(user);

        events.publishEvent(new AccountMailer.GoogleLinked(
                saved.getEmailAddress(), nameOf(saved), email));

        // The address is the person's own and already on the account; the subject
        // is deliberately not logged — it identifies them at Google, and the log
        // needs only to say that a join happened.
        log.info("Google account linked to user {}", saved.getId());

        return statusOf(saved);
    }

    /**
     * Sever the join.
     *
     * <p>Two conditions, and the first is the one that matters: an account with no
     * local password would have NO way in at all once Google is gone, so the
     * password comes first and the refusal says so. The second is the password
     * itself — the same proof the password change and the address change demand,
     * through the same throttle, so none of the three can be used to probe the
     * others.
     */
    @Transactional
    public GoogleLinkStatusResponse unlink(Long userId, String currentPassword) {
        User user = load(userId);

        if (user.getGoogleSubject() == null && user.getGoogleEmail() == null) {
            throw new IllegalArgumentException("Nalog nije povezan sa Google nalogom.");
        }

        if (user.getPasswordHash() == null) {
            throw new IllegalArgumentException(
                    "Prvo postavite lozinku za nalog — bez nje i bez Google naloga ne biste mogli da se prijavite.");
        }

        accountService.requireCurrentPassword(user, currentPassword);

        String was = user.getGoogleEmail();
        user.setGoogleSubject(null);
        user.setGoogleEmail(null);
        user.setGoogleLinkedAt(null);
        User saved = userRepository.save(user);

        events.publishEvent(new AccountMailer.GoogleUnlinked(
                saved.getEmailAddress(), nameOf(saved), was));

        log.info("Google account unlinked from user {}", saved.getId());

        return statusOf(saved);
    }

    /**
     * That no other profile already answers for this Google account.
     *
     * <p>Three ways it could, and all three are refused with a sentence rather
     * than left to a constraint violation:
     *
     * <ul>
     *   <li>the SUBJECT is joined elsewhere — the same Google account, named by
     *       the identifier that cannot be renamed;</li>
     *   <li>the ADDRESS is joined elsewhere — the same account, named the way a
     *       person would;</li>
     *   <li>the address is somebody else's ACCOUNT address. That profile signs in
     *       with Google through the fallback comparison today, so joining the
     *       address here would make two profiles answer for one Google account and
     *       the callback would have to choose between them.</li>
     * </ul>
     *
     * <p>The caller's own account address is of course fine — somebody whose
     * account address IS their Gmail may still record the join explicitly, and
     * after this they are matched by subject instead of by coincidence.
     */
    private void requireGoogleAccountIsFree(User user, String subject, String email) {
        Optional<User> bySubject = userRepository.findByGoogleSubject(subject);
        if (bySubject.isPresent() && !bySubject.get().getId().equals(user.getId())) {
            throw new IllegalArgumentException(
                    "Taj Google nalog je već povezan sa drugim profilom u aplikaciji.");
        }

        Optional<User> byGoogleEmail = userRepository.findByGoogleEmailIgnoreCase(email);
        if (byGoogleEmail.isPresent() && !byGoogleEmail.get().getId().equals(user.getId())) {
            throw new IllegalArgumentException(
                    "Taj Google nalog je već povezan sa drugim profilom u aplikaciji.");
        }

        Optional<User> byAccountEmail = userRepository.findByEmailAddressIgnoreCase(email);
        if (byAccountEmail.isPresent() && !byAccountEmail.get().getId().equals(user.getId())) {
            throw new IllegalArgumentException(
                    "Ta e-adresa je adresa drugog naloga u aplikaciji, pa se ne može povezati sa ovim.");
        }
    }

    private GoogleLinkStatusResponse statusOf(User user) {
        boolean linked = user.getGoogleSubject() != null || user.getGoogleEmail() != null;
        return new GoogleLinkStatusResponse(
                linked,
                linked ? user.getGoogleEmail() : null,
                linked ? user.getGoogleLinkedAt() : null,
                googleOAuthService.isConfigured(),
                user.getPasswordHash() != null);
    }

    private String nameOf(User user) {
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }

    private User load(Long userId) {
        if (userId == null) {
            throw new EntityNotFoundException("Niste prijavljeni.");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));
    }
}
