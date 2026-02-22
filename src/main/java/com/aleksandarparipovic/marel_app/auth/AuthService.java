package com.aleksandarparipovic.marel_app.auth;

import com.aleksandarparipovic.marel_app.auth.dto.LoginResponse;
import com.aleksandarparipovic.marel_app.auth.refresh.RefreshToken;
import com.aleksandarparipovic.marel_app.auth.refresh.RefreshTokenService;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public LoginResponse login(String username, String password, String ipAddress, String userAgent) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));

        if (user.getArchivedAt() != null || !Boolean.TRUE.equals(user.getActive())) {
            throw new IllegalArgumentException("User is inactive");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService
                .issueForUser(user, ipAddress, userAgent)
                .getRawToken();

        return new LoginResponse(
                accessToken,
                refreshToken,
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

        if (user.getArchivedAt() != null || !Boolean.TRUE.equals(user.getActive())) {
            throw new IllegalArgumentException("User is inactive");
        }

        String accessToken = jwtService.generateAccessToken(user);

        return new LoginResponse(
                accessToken,
                issuedToken.getRawToken(),
                "Bearer",
                jwtService.getAccessTokenTtlSeconds()
        );
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken, "LOGOUT");
    }
}
