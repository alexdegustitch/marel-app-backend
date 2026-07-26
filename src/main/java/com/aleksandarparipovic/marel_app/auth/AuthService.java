package com.aleksandarparipovic.marel_app.auth;

import com.aleksandarparipovic.marel_app.auth.dto.LoginResponse;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
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

    @Transactional
    public LoginResponse login(String username, String password, String ipAddress, String userAgent) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));

        // Credentials are verified BEFORE the account state is revealed: telling an
        // anonymous caller "this account is pending approval" for a password they do
        // not know would leak which usernames exist.
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
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

        Role role = roleRepository.findById(request.getRoleId())
                .orElseThrow(() -> new IllegalArgumentException("Uloga nije pronađena"));

        if (EXCLUDED_ROLE.equalsIgnoreCase(role.getRoleName())) {
            throw new IllegalArgumentException("Izabrana uloga nije dostupna za registraciju");
        }

        String username = generateUniqueUsername(request.getFirstName(), request.getLastName());

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

    private String generateUniqueUsername(String firstName, String lastName) {
        String base = UsernameGenerator.baseUsername(firstName, lastName);
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
                .username(generateUniqueUsername(safeFirst, safeLast))
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
