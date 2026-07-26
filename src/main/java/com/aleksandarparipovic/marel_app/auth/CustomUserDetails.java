package com.aleksandarparipovic.marel_app.auth;

import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserAccountStatus;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class CustomUserDetails implements UserDetails {

    @Getter
    private final Long id;
    private final String username;
    private final String password;
    @Getter
    private final UserAccountStatus accountStatus;
    private final Collection<? extends GrantedAuthority> authorities;

    public CustomUserDetails(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.accountStatus = user.getAccountStatus();
        this.authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().getRoleName())
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Only an ACTIVE account may use the application. A still-pending, declined,
     * suspended or archived account is disabled even if it somehow presents a
     * valid access token — tokens live for 15 minutes, so a decision taken during
     * that window must take effect immediately rather than at expiry.
     */
    @Override
    public boolean isEnabled() {
        return accountStatus != null && accountStatus.canAuthenticate();
    }
}
