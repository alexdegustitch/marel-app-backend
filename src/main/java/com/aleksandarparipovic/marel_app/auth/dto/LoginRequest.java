package com.aleksandarparipovic.marel_app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    /**
     * "Sačuvaj me prijavljenog". True (or absent, for older clients) keeps the
     * refresh cookie for its full TTL; false makes it a session cookie that
     * dies with the browser.
     */
    private Boolean rememberMe;
}
