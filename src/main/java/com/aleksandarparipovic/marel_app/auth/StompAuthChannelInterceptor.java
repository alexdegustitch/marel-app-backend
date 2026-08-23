package com.aleksandarparipovic.marel_app.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * The WebSocket half of {@link JwtAuthenticationFilter}: the same access token,
 * checked the same way, on a transport that has no servlet filter chain.
 *
 * <p>Authentication happens once, on the STOMP CONNECT frame, and the resulting
 * principal is what the broker uses to route user destinations. Without it there
 * is no such thing as "this user's queue" — every subscriber of a destination
 * receives everything sent to it.
 *
 * <p>An unauthenticated CONNECT is REFUSED rather than allowed through as
 * anonymous. Before this existed, anyone who could reach the port could
 * subscribe to the report topics and watch employee and shift ids go past.
 *
 * <p><b>Known limit:</b> the check is at connect time only. An access token that
 * expires during a long-lived connection does not close it; the connection is
 * re-authenticated when it next reconnects. Closing sessions the moment a token
 * expires would need a heartbeat of its own, and is not what protects the data
 * here — the routing does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final CustomUserDetailsService customUserDetailsService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        accessor.setUser(authenticate(accessor));
        return message;
    }

    private Authentication authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTHORIZATION);

        if (header == null || !header.startsWith(BEARER)) {
            throw new StompAuthenticationException("Nedostaje pristupni token.");
        }

        String token = header.substring(BEARER.length());
        if (!jwtService.isAccessTokenValid(token)) {
            throw new StompAuthenticationException("Pristupni token nije važeći.");
        }

        String username = jwtService.extractUsername(token);
        if (username == null) {
            throw new StompAuthenticationException("Pristupni token nema korisnika.");
        }

        try {
            var userDetails = customUserDetailsService.loadUserByUsername(username);

            // A token outlives a status change by up to its TTL, so an account
            // that stopped being ACTIVE must be refused here too — exactly as the
            // servlet filter refuses it.
            if (!userDetails.isEnabled()) {
                throw new StompAuthenticationException("Nalog nije aktivan.");
            }

            return new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
        } catch (UsernameNotFoundException e) {
            throw new StompAuthenticationException("Korisnik ne postoji.");
        }
    }
}
