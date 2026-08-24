package com.aleksandarparipovic.marel_app.account;

import com.aleksandarparipovic.marel_app.account.dto.PasswordChangeRequest;
import com.aleksandarparipovic.marel_app.account.dto.ProfileUpdateRequest;
import com.aleksandarparipovic.marel_app.auth.ratelimit.AuthAttemptLimiter;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserMapper;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user.dto.UserDto;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * What a person may change about their own account without asking anybody.
 *
 * <p>Their name, the name they are shown by, and their telephone number. These
 * are contact details: getting one wrong inconveniences the person themselves and
 * nobody else, so making them queue behind an administrator was friction with
 * nothing on the other side of it.
 *
 * <p>Three things are deliberately NOT here. The e-mail address is a sign-in
 * credential and has its own guarded exchange ({@link EmailChangeService}). The
 * username never changes at all ({@link UsernameRules}). The role is what the
 * application lets somebody do, and nobody grants themselves permissions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthAttemptLimiter attemptLimiter;

    /**
     * Update one's own contact details.
     *
     * <p>Null leaves a field alone, as everywhere else in this codebase. Blanking a
     * name is refused rather than accepted — `first_name` and `last_name` are NOT
     * NULL and feed {@code full_name}, which about forty queries order by; an empty
     * name would make somebody disappear from every list they belong in.
     */
    @Transactional
    public UserDto updateOwnProfile(Long userId, ProfileUpdateRequest request) {
        User user = load(userId);

        if (request.getFirstName() != null) {
            user.setFirstName(requireText(request.getFirstName(), "Ime ne sme biti prazno."));
        }
        if (request.getLastName() != null) {
            user.setLastName(requireText(request.getLastName(), "Prezime ne sme biti prazno."));
        }
        if (request.getMobilePhone() != null) {
            // Blank IS meaningful here: it means "I have no number on file",
            // which is a legitimate answer and is stored as null rather than "".
            String phone = request.getMobilePhone().trim();
            user.setMobilePhone(phone.isEmpty() ? null : phone);
        }
        if (request.getDisplayName() != null) {
            String displayName = request.getDisplayName().trim();
            user.setDisplayName(displayName.isEmpty() ? null : displayName);
        }

        /*
         * FLUSHED, not just saved. `full_name` is GENERATED ALWAYS in the database
         * and Hibernate only re-reads it after the UPDATE statement actually runs.
         * With a plain save() that happens at the end of the transaction — after
         * this line — so the response would carry the NEW first and last name
         * beside the OLD full name, which is what the profile page puts in its
         * heading.
         */
        return UserMapper.toDto(userRepository.saveAndFlush(user));
    }

    /**
     * Change one's own password.
     *
     * <p>The current password is required even though there is already a session:
     * a session is "this browser was signed in at some point", and a laptop left
     * open on a factory floor is exactly the case this protects against. Knowing
     * the password is the only evidence that the person at the keyboard is the
     * account's owner.
     *
     * <p>Wrong attempts feed the SAME limiter the sign-in screen uses, so this
     * endpoint cannot be used as an unthrottled oracle for guessing a password
     * that the login page would have slowed down.
     */
    @Transactional
    public void changeOwnPassword(Long userId, PasswordChangeRequest request) {
        User user = load(userId);

        if (user.getPasswordHash() == null) {
            /*
             * A Google-provisioned account has no local password to change, and
             * setting one here would quietly open a SECOND way into an account
             * whose owner believes it is reachable only through Google.
             */
            throw new IllegalArgumentException(
                    "Vaš nalog koristi prijavu preko Google-a i nema lozinku u ovoj aplikaciji.");
        }

        requireCurrentPassword(user, request.getCurrentPassword());

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Nova lozinka i njena potvrda se ne poklapaju.");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Nova lozinka mora biti različita od trenutne.");
        }

        List<String> problems = PasswordPolicy.violations(
                request.getNewPassword(), user.getUsername(), user.getEmailAddress());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Deliberately no hash, no password, and no length in the log line.
        log.info("Password changed by user {}", userId);
    }

    /**
     * Proof that the person at the keyboard is the account's owner.
     *
     * <p>Shared by the password change and the start of an address change, so both
     * throttle through one counter and neither can be used to probe the other.
     */
    void requireCurrentPassword(User user, String currentPassword) {
        String limiterKey = "account:" + user.getId();

        long blockedFor = attemptLimiter.blockedForSeconds(limiterKey);
        if (blockedFor > 0) {
            throw new IllegalArgumentException(
                    "Previše pogrešnih pokušaja. Pokušajte ponovo za " + blockedFor + " s.");
        }

        if (currentPassword == null
                || user.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            attemptLimiter.recordFailure(limiterKey);
            throw new IllegalArgumentException("Trenutna lozinka nije tačna.");
        }

        attemptLimiter.recordSuccess(limiterKey);
    }

    private User load(Long userId) {
        if (userId == null) {
            throw new EntityNotFoundException("Niste prijavljeni.");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));
    }

    private String requireText(String value, String message) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }
}
