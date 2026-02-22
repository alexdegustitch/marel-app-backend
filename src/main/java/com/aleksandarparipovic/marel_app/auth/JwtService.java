package com.aleksandarparipovic.marel_app.auth;

import com.aleksandarparipovic.marel_app.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    private final Key signingKey;
    private final long accessTokenTtlSeconds;

    public JwtService(
            @Value("${app.security.jwt.secret}") String secret,
            @Value("${app.security.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds
    ) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(accessTokenTtlSeconds);

        return Jwts.builder()
                .setSubject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole().getRoleName())
                .claim("typ", "access")
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expiresAt))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token)
                .map(Claims::getSubject)
                .orElse(null);
    }

    public boolean isAccessTokenValid(String token) {
        return parseClaims(token)
                .filter(this::isNotExpired)
                .map(claims -> "access".equals(claims.get("typ", String.class)))
                .orElse(false);
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    private Optional<Claims> parseClaims(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return Optional.of(claims);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private boolean isNotExpired(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration != null && expiration.after(new Date());
    }
}
