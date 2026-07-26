package com.aleksandarparipovic.marel_app.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_ID_ATTRIBUTE = "marel.sessionId";

    private final JwtService jwtService;
    private final CustomUserDetailsService customUserDetailsService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        if (!jwtService.isAccessTokenValid(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String username = jwtService.extractUsername(token);

        // The session (refresh-token family) the caller is authenticated on. Exposed
        // as a request attribute so the heartbeat endpoint resolves the session from
        // the verified token rather than from anything the client can choose.
        request.setAttribute(SESSION_ID_ATTRIBUTE, jwtService.extractSessionId(token));

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                var userDetails = customUserDetailsService.loadUserByUsername(username);

                // A token outlives a status change by up to its 15-minute TTL, so
                // an account that stopped being ACTIVE (declined, suspended,
                // archived) must be refused here and not merely at next login.
                if (!userDetails.isEnabled()) {
                    filterChain.doFilter(request, response);
                    return;
                }

                var auth = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (UsernameNotFoundException ex) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
