package com.aleksandarparipovic.marel_app.auth;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Are you still the person who signed in?"
 *
 * <p>For the few actions where holding the permission is not quite enough —
 * where the consequence is public, or cannot be taken back by simply doing the
 * opposite. Freezing a payroll is the first: it publishes the month to the
 * employee and closes it to every further edit, and reopening it leaves both
 * steps in the history for good.
 *
 * <p><b>An authentication concern, so it lives at the HTTP boundary.</b> The
 * controllers call it; the services do not. A service method that demanded a
 * password would also demand one from every internal caller that has no session
 * and no password to give — the payroll integration tests among them — and the
 * usual answer to that is an overload without the check, which is a back door
 * with a comment on it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StepUpVerificationService {

    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;
    /** The same encoder sign-in uses, so a password that works there works here. */
    private final PasswordEncoder passwordEncoder;

    /**
     * Confirms the password of the account making this request.
     *
     * <p>Read from the SESSION's user, never from a name the caller sends: a
     * step-up that let the caller nominate whose password to check would be a
     * way to test other people's.
     *
     * @throws ConflictException when it is missing or wrong — the same shape of
     *         refusal as any other rule the screen has to show a sentence for.
     */
    @Transactional(readOnly = true)
    public void requireCurrentPassword(String password) {
        if (password == null || password.isBlank()) {
            throw new ConflictException("Unesite lozinku da biste potvrdili radnju.");
        }

        Long userId = currentUserService.getCurrentUserId();
        if (userId == null) {
            throw new ConflictException("Radnju može da potvrdi samo prijavljeni korisnik.");
        }

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new ConflictException("Korisnik nije pronađen."));

        if (actor.getPasswordHash() == null
                || !passwordEncoder.matches(password, actor.getPasswordHash())) {
            // The caller is told only that it is wrong. The log names the account,
            // because an administrator reading their own server log is not the
            // person being guarded against.
            log.info("Step-up refused: wrong password for '{}'", actor.getUsername());
            throw new ConflictException("Lozinka nije tačna.");
        }
    }
}
