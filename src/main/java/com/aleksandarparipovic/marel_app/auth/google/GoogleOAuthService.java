package com.aleksandarparipovic.marel_app.auth.google;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * Talks to Google's OAuth2 endpoints directly over HTTP (authorization-code flow)
 * rather than pulling in Spring Security's OAuth2 client — this backend issues its
 * own JWTs and never needs a browser session against Spring Security itself.
 */
@Slf4j
@Service
public class GoogleOAuthService {

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo";

    private final RestClient restClient = RestClient.create();

    @Value("${google.oauth.client-id:}")
    private String clientId;

    @Value("${google.oauth.client-secret:}")
    private String clientSecret;

    @Value("${google.oauth.redirect-uri:http://localhost:8080/api/auth/google/callback}")
    private String redirectUri;

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public URI buildAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTH_ENDPOINT)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("prompt", "select_account")
                .build()
                .toUri();
    }

    public GoogleUserInfo exchangeCodeForUserInfo(String code) {
        @SuppressWarnings("unchecked")
        Map<String, Object> tokenResponse = restClient.post()
                .uri(TOKEN_ENDPOINT)
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .body(
                        "code=" + code
                                + "&client_id=" + clientId
                                + "&client_secret=" + clientSecret
                                + "&redirect_uri=" + redirectUri
                                + "&grant_type=authorization_code"
                )
                .retrieve()
                .body(Map.class);

        if (tokenResponse == null || tokenResponse.get("access_token") == null) {
            throw new IllegalStateException("Google nije vratio access token");
        }

        String googleAccessToken = String.valueOf(tokenResponse.get("access_token"));

        @SuppressWarnings("unchecked")
        Map<String, Object> userInfo = restClient.get()
                .uri(USERINFO_ENDPOINT)
                .header("Authorization", "Bearer " + googleAccessToken)
                .retrieve()
                .body(Map.class);

        if (userInfo == null || userInfo.get("email") == null) {
            throw new IllegalStateException("Google nije vratio email adresu");
        }

        return new GoogleUserInfo(
                String.valueOf(userInfo.get("email")),
                Boolean.TRUE.equals(userInfo.get("email_verified")),
                userInfo.get("given_name") != null ? String.valueOf(userInfo.get("given_name")) : null,
                userInfo.get("family_name") != null ? String.valueOf(userInfo.get("family_name")) : null
        );
    }
}
