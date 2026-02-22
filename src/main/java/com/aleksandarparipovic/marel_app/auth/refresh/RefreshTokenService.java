package com.aleksandarparipovic.marel_app.auth.refresh;

import com.aleksandarparipovic.marel_app.user.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${app.security.refresh-token.ttl-seconds:2592000}")
    private long refreshTokenTtlSeconds;

    @Transactional
    public IssuedRefreshToken issueForUser(User user, String ipAddress, String userAgent) {
        String rawToken = generateRawToken();
        String hashedToken = hashToken(rawToken);

        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hashedToken)
                .familyId(UUID.randomUUID().toString())
                .expiresAt(OffsetDateTime.now().plusSeconds(refreshTokenTtlSeconds))
                .createdIp(trim(ipAddress, 100))
                .createdUserAgent(trim(userAgent, 500))
                .build();

        refreshTokenRepository.save(token);
        return new IssuedRefreshToken(rawToken, token);
    }

    @Transactional
    public IssuedRefreshToken rotate(String rawToken, String ipAddress, String userAgent) {
        RefreshToken current = findByRawTokenForUpdate(rawToken);

        if (!current.isActive()) {
            refreshTokenRepository.revokeAllByFamilyId(
                    current.getFamilyId(),
                    OffsetDateTime.now(),
                    "Refresh token reuse detected"
            );
            throw new IllegalArgumentException("Invalid refresh token");
        }

        String replacementRawToken = generateRawToken();
        String replacementHash = hashToken(replacementRawToken);

        RefreshToken replacement = RefreshToken.builder()
                .user(current.getUser())
                .tokenHash(replacementHash)
                .familyId(current.getFamilyId())
                .expiresAt(OffsetDateTime.now().plusSeconds(refreshTokenTtlSeconds))
                .createdIp(trim(ipAddress, 100))
                .createdUserAgent(trim(userAgent, 500))
                .build();
        refreshTokenRepository.save(replacement);

        current.setRevokedAt(OffsetDateTime.now());
        current.setRevokedReason("ROTATED");
        current.setReplacedByTokenHash(replacementHash);

        return new IssuedRefreshToken(replacementRawToken, replacement);
    }

    @Transactional
    public void revoke(String rawToken, String reason) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }

        String hashed = hashToken(rawToken);
        refreshTokenRepository.findByTokenHashForUpdate(hashed)
                .ifPresent(token -> {
                    if (token.getRevokedAt() == null) {
                        token.setRevokedAt(OffsetDateTime.now());
                        token.setRevokedReason(reason);
                    }
                });
    }

    private RefreshToken findByRawTokenForUpdate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token is required");
        }

        String hashed = hashToken(rawToken);
        return refreshTokenRepository.findByTokenHashForUpdate(hashed)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));
    }

    private String generateRawToken() {
        byte[] bytes = new byte[64];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    @Getter
    @RequiredArgsConstructor
    public static class IssuedRefreshToken {
        private final String rawToken;
        private final RefreshToken token;
    }
}
