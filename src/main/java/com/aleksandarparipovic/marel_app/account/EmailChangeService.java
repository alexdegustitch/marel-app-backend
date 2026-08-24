package com.aleksandarparipovic.marel_app.account;

import com.aleksandarparipovic.marel_app.account.dto.PendingEmailChangeResponse;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * Changing the address an account signs in with.
 *
 * <p>The exchange, and why each step is there:
 *
 * <ol>
 *   <li><b>The password, again.</b> A live session is not proof of identity — it
 *       is proof that somebody signed in on this machine at some point. An
 *       unattended terminal on a factory floor is exactly the case here.</li>
 *   <li><b>A code, to the NEW address.</b> Proof that the person can actually read
 *       the mailbox they are moving the account to. Without it, a typo locks
 *       somebody out of their own account permanently, and a malicious change
 *       hands it to an address the attacker owns.</li>
 *   <li><b>The old address stays live throughout.</b> Nothing on {@code users}
 *       moves until the code comes back. A half-finished change leaves the
 *       account exactly as it was.</li>
 *   <li><b>The old address is told afterwards.</b> The new one already knows — it
 *       just confirmed. The notice to the OLD mailbox is the only warning the
 *       rightful owner gets if this was not them.</li>
 *   <li><b>Other sessions end.</b> If the change was made by somebody who should
 *       not have, their session is the one that stops working.</li>
 * </ol>
 *
 * <p><b>Google accounts cannot do this at all.</b> Their address is not merely a
 * contact detail — it IS the identity Google asserts, and the account has no
 * password to confirm with. Pointing such an account at a different address would
 * leave a login that no longer corresponds to anybody.
 *
 * <p>The audit trail needs nothing from this class: {@code trg_audit_logs_users}
 * already records the old and new address, when, and who, on the UPDATE below.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailChangeService {

    /**
     * Wrong codes allowed against one request. Six digits is a million
     * possibilities; this is what makes that one guess at a time rather than a
     * million. Past it the request is dead and a new one needs the password again.
     */
    static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final EmailChangeRequestRepository requestRepository;
    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final AccountSessionRevoker sessionRevoker;

    @Value("${app.account.email-change-minutes:30}")
    private int validMinutes;

    /** What the screen shows while a change is waiting. Never the code. */
    @Transactional(readOnly = true)
    public Optional<PendingEmailChangeResponse> pending(Long userId) {
        OffsetDateTime now = OffsetDateTime.now();
        return requestRepository.findOpenForUser(userId)
                .filter(request -> request.isLive(now))
                .map(request -> new PendingEmailChangeResponse(
                        request.getNewEmail(),
                        request.getExpiresAt(),
                        Math.max(0, MAX_ATTEMPTS - request.getAttempts())));
    }

    /**
     * Begin the change: verify the password, park the request, send the code.
     */
    @Transactional
    public PendingEmailChangeResponse start(Long userId, String rawNewEmail, String currentPassword) {
        User user = load(userId);

        if (user.getPasswordHash() == null) {
            throw new IllegalArgumentException(
                    "Nalog koristi prijavu preko Google-a, pa se e-adresa ne može menjati ovde.");
        }

        String newEmail = normalise(rawNewEmail);
        if (newEmail.isEmpty() || newEmail.indexOf('@') < 1) {
            throw new IllegalArgumentException("E-adresa nije ispravna.");
        }
        if (newEmail.equalsIgnoreCase(user.getEmailAddress())) {
            throw new IllegalArgumentException("To je već e-adresa vašeg naloga.");
        }

        accountService.requireCurrentPassword(user, currentPassword);

        requireAddressFree(newEmail, userId);

        /*
         * Supersede rather than refuse. Somebody who mistyped the address expects
         * to be able to start again immediately; making them find a "cancel"
         * button first is a dead end they will read as the feature being broken.
         * The unique index allows exactly one live request, so the old one has to
         * go — and cancelling it is also what invalidates the code already sent.
         */
        OffsetDateTime now = OffsetDateTime.now();
        requestRepository.findOpenForUser(userId).ifPresent(existing -> {
            existing.setCancelledAt(now);
            requestRepository.saveAndFlush(existing);
        });

        String code = newCode();
        EmailChangeRequest request = requestRepository.save(EmailChangeRequest.builder()
                .user(user)
                .newEmail(newEmail)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(now.plusMinutes(validMinutes))
                .build());

        events.publishEvent(new AccountMailer.CodeIssued(
                newEmail, displayNameOf(user), code, validMinutes));

        log.info("Email change started for user {} (code sent to the new address)", userId);

        return new PendingEmailChangeResponse(newEmail, request.getExpiresAt(), MAX_ATTEMPTS);
    }

    /**
     * Finish the change, if the code is right.
     *
     * @param currentSessionId the session doing this, kept alive while the others
     *                         are ended. Null ends every session including this
     *                         one, which is the safe direction to fail in.
     */
    @Transactional
    public void confirm(Long userId, String code, String currentSessionId) {
        User user = load(userId);
        OffsetDateTime now = OffsetDateTime.now();

        EmailChangeRequest request = requestRepository.findOpenForUser(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nema započete promene e-adrese."));

        if (request.isExpired(now)) {
            throw new IllegalArgumentException(
                    "Kod je istekao. Pokrenite promenu ponovo.");
        }

        if (request.getAttempts() >= MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "Previše pogrešnih pokušaja. Pokrenite promenu ponovo.");
        }

        if (code == null || !passwordEncoder.matches(code.trim(), request.getCodeHash())) {
            request.setAttempts(request.getAttempts() + 1);
            requestRepository.save(request);

            int left = Math.max(0, MAX_ATTEMPTS - request.getAttempts());
            throw new IllegalArgumentException(left == 0
                    ? "Kod nije tačan. Pokrenite promenu ponovo."
                    : "Kod nije tačan. Preostalo pokušaja: " + left + ".");
        }

        // Checked AGAIN, not only at the start: minutes or days may have passed,
        // and somebody else may have taken the address in between. The unique
        // constraint would catch it, but as a constraint violation nobody can read.
        requireAddressFree(request.getNewEmail(), userId);

        String oldEmail = user.getEmailAddress();
        user.setEmailAddress(request.getNewEmail());
        userRepository.save(user);

        request.setConfirmedAt(now);
        requestRepository.save(request);

        events.publishEvent(new AccountMailer.ChangeCompleted(
                oldEmail, displayNameOf(user), request.getNewEmail()));

        sessionRevoker.endOtherSessions(userId, currentSessionId, "email-changed");

        log.info("Email changed for user {}", userId);
    }

    /** Abandon a change in progress. The code stops working immediately. */
    @Transactional
    public void cancel(Long userId) {
        requestRepository.findOpenForUser(userId).ifPresent(request -> {
            request.setCancelledAt(OffsetDateTime.now());
            requestRepository.save(request);
        });
    }

    private void requireAddressFree(String email, Long userId) {
        userRepository.findByEmailAddressIgnoreCase(email)
                .filter(other -> !other.getId().equals(userId))
                .ifPresent(other -> {
                    // Deliberately does not name the other account. "Somebody else
                    // uses this address" is all the person needs, and saying whose
                    // would turn this into a way to look up who holds an address.
                    throw new ConflictException("Ta e-adresa se već koristi.");
                });
    }

    /**
     * Six digits, from a cryptographic source.
     *
     * <p>{@code SecureRandom}, not {@code Math.random}: a predictable code is the
     * same as no code, and this one is the only thing standing between a stolen
     * session and a stolen account.
     */
    private String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private String displayNameOf(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return user.getFullName();
    }

    private String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private User load(Long userId) {
        if (userId == null) {
            throw new EntityNotFoundException("Niste prijavljeni.");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));
    }
}
