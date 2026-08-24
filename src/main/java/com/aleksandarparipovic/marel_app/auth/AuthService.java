package com.aleksandarparipovic.marel_app.auth;

import com.aleksandarparipovic.marel_app.auth.dto.LoginResponse;
import com.aleksandarparipovic.marel_app.account.PasswordPolicy;
import com.aleksandarparipovic.marel_app.account.UsernameRules;
import com.aleksandarparipovic.marel_app.auth.dto.RegisterRequest;
import com.aleksandarparipovic.marel_app.auth.dto.RegisterResponse;
import com.aleksandarparipovic.marel_app.auth.refresh.RefreshToken;
import com.aleksandarparipovic.marel_app.auth.refresh.RefreshTokenService;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import com.aleksandarparipovic.marel_app.user.UsernameGenerator;
import com.aleksandarparipovic.marel_app.user_registration_request.UserRegistrationRequestService;
import com.aleksandarparipovic.marel_app.user_session.UserSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private static final String EXCLUDED_ROLE = "developer";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserRegistrationRequestService registrationRequestService;
    private final UserSessionService userSessionService;

    /**
     * Sign in with a username OR an e-mail address, whichever the person types.
     *
     * <p>The form asks for one field and people put either in it — and the two are not
     * interchangeable for everyone: most accounts here were created with the e-mail as the
     * username, but the bootstrap admin is "admin" with the e-mail "admin@marel.local". A
     * lookup by username alone turned typing that account's own e-mail into "invalid
     * username or password".
     *
     * <p>Both lookups ignore case, which is what the database already guarantees: the unique
     * indexes are on {@code lower(username)} and {@code lower(email_address)}, so neither can
     * match two people. The input is trimmed because a trailing space pasted from a password
     * manager is not a different account.
     */
    @Transactional
    public LoginResponse login(String username, String password, String ipAddress, String userAgent) {
        String identifier = username == null ? "" : username.trim();

        User user = userRepository.findByUsernameIgnoreCase(identifier)
                .or(() -> userRepository.findByEmailAddressIgnoreCase(identifier))
                .orElseThrow(() -> {
                    // The CALLER is told nothing beyond "these credentials are wrong" — see
                    // below — but the log says which half failed, because an administrator
                    // reading their own server log is not the person being guarded against.
                    log.info("Sign-in failed: no account matches '{}'", identifier);
                    return new IllegalArgumentException("Invalid username or password");
                });

        // Credentials are verified BEFORE the account state is revealed: telling an
        // anonymous caller "this account is pending approval" for a password they do
        // not know would leak which usernames exist. For the same reason the message is
        // identical whether the account was not found or the password did not match —
        // anything else lets a stranger harvest valid usernames by watching the wording.
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            log.info("Sign-in failed: wrong password for '{}'", user.getUsername());
            throw new IllegalArgumentException("Invalid username or password");
        }

        requireUsableAccount(user);

        return issueTokens(user, ipAddress, userAgent);
    }

    private LoginResponse issueTokens(User user, String ipAddress, String userAgent) {
        RefreshTokenService.IssuedRefreshToken issued =
                refreshTokenService.issueForUser(user, ipAddress, userAgent);
        RefreshToken token = issued.getToken();

        // One session per login, identified by the refresh-token family so it
        // survives every subsequent token rotation.
        userSessionService.createForLogin(
                user, token.getFamilyId(), token.getExpiresAt(), ipAddress, userAgent);

        String accessToken = jwtService.generateAccessToken(user, token.getFamilyId());

        return new LoginResponse(
                accessToken,
                issued.getRawToken(),
                "Bearer",
                jwtService.getAccessTokenTtlSeconds()
        );
    }

    @Transactional
    public LoginResponse refresh(String rawRefreshToken, String ipAddress, String userAgent) {
        RefreshTokenService.IssuedRefreshToken issuedToken =
                refreshTokenService.rotate(rawRefreshToken, ipAddress, userAgent);

        RefreshToken refreshToken = issuedToken.getToken();
        User user = refreshToken.getUser();

        requireUsableAccount(user);

        // Rotation keeps the same family, so the session continues rather than
        // being replaced — that is what makes presence stable across refreshes.
        String accessToken = jwtService.generateAccessToken(user, refreshToken.getFamilyId());

        return new LoginResponse(
                accessToken,
                issuedToken.getRawToken(),
                "Bearer",
                jwtService.getAccessTokenTtlSeconds()
        );
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        // Close the session before revoking, while the token can still resolve it.
        refreshTokenService.findFamilyId(rawRefreshToken).ifPresent(familyId ->
                refreshTokenService.findUserId(rawRefreshToken).ifPresent(userId ->
                        userSessionService.logout(familyId, userId)));

        refreshTokenService.revoke(rawRefreshToken, "LOGOUT");
    }

    /**
     * Public self-registration. New accounts are created inactive (active=false) and
     * must be approved/activated by an admin before their first login.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Šifre se ne poklapaju");
        }

        if (userRepository.existsByEmailAddress(request.getEmailAddress())) {
            throw new IllegalArgumentException("Email adresa je već u upotrebi");
        }

        String username = resolveRequestedUsername(
                request.getUsername(), request.getEmailAddress(),
                request.getFirstName(), request.getLastName());

        List<String> passwordProblems = PasswordPolicy.violations(
                request.getPassword(), username, request.getEmailAddress());
        if (!passwordProblems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", passwordProblems));
        }

        Role role = roleRepository.findById(request.getRoleId())
                .orElseThrow(() -> new IllegalArgumentException("Uloga nije pronađena"));

        if (EXCLUDED_ROLE.equalsIgnoreCase(role.getRoleName())) {
            throw new IllegalArgumentException("Izabrana uloga nije dostupna za registraciju");
        }

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .mobilePhone(request.getMobilePhone())
                .emailAddress(request.getEmailAddress().trim())
                .role(role)
                .accountStatus(UserAccountStatus.PENDING_APPROVAL)
                .active(false)
                .build();

        User saved = userRepository.save(user);

        // Same transaction as the user insert: an account without a review request
        // would be invisible to administrators and could never be approved.
        registrationRequestService.openFor(saved);

        return new RegisterResponse(
                saved.getId(),
                saved.getUsername(),
                "Registracija je uspešna. Nalog čeka odobrenje administratora."
        );
    }

    /**
     * Only an ACTIVE account may obtain tokens. The message distinguishes "waiting
     * for approval" from "refused" so the client can show the right screen — this
     * is an account-state answer to an authenticated caller, not an enumeration
     * oracle, because it is only reached after the password has already matched.
     */
    private void requireUsableAccount(User user) {
        UserAccountStatus status = user.getAccountStatus();

        if (status == UserAccountStatus.ACTIVE && user.getArchivedAt() == null) {
            return;
        }

        throw new AccountNotUsableException(
                status == null ? UserAccountStatus.SUSPENDED : status,
                switch (status == null ? UserAccountStatus.SUSPENDED : status) {
                    case PENDING_APPROVAL -> "Nalog čeka odobrenje administratora.";
                    case DECLINED -> "Registracija ovog naloga je odbijena.";
                    case ARCHIVED -> "Nalog je arhiviran.";
                    default -> "Nalog nije aktivan.";
                }
        );
    }

    /**
     * The username this registration ends up with.
     *
     * <p>A TYPED username is taken as typed — validated and refused if it breaks
     * the rules or is taken, never silently corrected. Somebody whose chosen name
     * was quietly turned into something else would be signing in for the first
     * time with a username they have never seen.
     *
     * <p>An ABSENT one is derived from the e-mail address and, only then, made
     * unique with a numeric suffix. That is safe to do silently because the person
     * expressed no preference — and the form shows them the suggestion anyway.
     */
    private String resolveRequestedUsername(
            String requested, String email, String firstName, String lastName
    ) {
        if (requested != null && !requested.isBlank()) {
            String username = requested.trim().toLowerCase(java.util.Locale.ROOT);

            if (!UsernameRules.isValid(username)) {
                throw new IllegalArgumentException(UsernameRules.requirement());
            }
            if (userRepository.existsByUsername(username)) {
                throw new IllegalArgumentException("Korisničko ime je zauzeto.");
            }
            return username;
        }

        return makeUnique(UsernameRules.suggestFrom(email, firstName, lastName));
    }

    /** Appends the smallest numeric suffix that is free. */
    private String makeUnique(String base) {
        String candidate = base;
        int suffix = 1;

        while (userRepository.existsByUsername(candidate)) {
            suffix++;
            candidate = base + suffix;
        }

        return candidate;
    }


    /**
     * Creates the local account for a Google-verified identity once the user has
     * picked a role (and optionally a phone number) on the "complete your profile"
     * step — same active=false-until-admin-approval gate as email registration.
     */
    @Transactional
    public RegisterResponse completeGoogleRegistration(
            String email, String firstName, String lastName, Long roleId, String mobilePhone
    ) {
        if (userRepository.existsByEmailAddress(email)) {
            throw new IllegalArgumentException("Nalog sa ovom email adresom već postoji");
        }

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Uloga nije pronađena"));

        if (EXCLUDED_ROLE.equalsIgnoreCase(role.getRoleName())) {
            throw new IllegalArgumentException("Izabrana uloga nije dostupna za registraciju");
        }

        String safeFirst = (firstName == null || firstName.isBlank()) ? "Google" : firstName.trim();
        String safeLast = (lastName == null || lastName.isBlank()) ? "Korisnik" : lastName.trim();

        User user = User.builder()
                // Same rule as e-mail registration: the address is where the
                // suggestion comes from. Google gives us a verified address and
                // often no usable name at all, so deriving from the name here was
                // the weaker of the two sources.
                .username(makeUnique(UsernameRules.suggestFrom(email, safeFirst, safeLast)))
                .passwordHash(null)
                .firstName(safeFirst)
                .lastName(safeLast)
                .mobilePhone(mobilePhone)
                .emailAddress(email)
                .role(role)
                .accountStatus(UserAccountStatus.PENDING_APPROVAL)
                .active(false)
                .build();

        User saved = userRepository.save(user);

        // Same transaction as the user insert: an account without a review request
        // would be invisible to administrators and could never be approved.
        registrationRequestService.openFor(saved);

        return new RegisterResponse(
                saved.getId(),
                saved.getUsername(),
                "Registracija je uspešna. Nalog čeka odobrenje administratora."
        );
    }

    /**
     * "Continue with Google" from the LOGIN screen — never provisions an account.
     * If no local user matches the Google-verified email, the caller must tell the
     * user no such account exists rather than silently creating one.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<User> findGoogleUserForLogin(String email) {
        return userRepository.findByEmailAddressIgnoreCase(email);
    }

    @Transactional
    public LoginResponse issueTokensForUserId(Long userId, String ipAddress, String userAgent) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        requireUsableAccount(user);

        return issueTokens(user, ipAddress, userAgent);
    }
}
