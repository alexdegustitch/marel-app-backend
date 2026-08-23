package com.aleksandarparipovic.marel_app.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who is allowed to open a WebSocket.
 *
 * <p>The point of this interceptor is that a session gets a PRINCIPAL. Without
 * one there is no per-user destination, and everything sent to "a user" reaches
 * whoever happens to be subscribed. So most of what is asserted here is the
 * refusals: no token, a bad token, and an account that has stopped being usable
 * since its token was issued.
 */
class StompAuthChannelInterceptorTest {

    private static final String VALID = "valid-token";
    private static final String USERNAME = "aparipovic";

    private JwtService jwtService;
    private CustomUserDetailsService userDetailsService;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userDetailsService = mock(CustomUserDetailsService.class);
        interceptor = new StompAuthChannelInterceptor(jwtService, userDetailsService);

        when(jwtService.isAccessTokenValid(VALID)).thenReturn(true);
        when(jwtService.extractUsername(VALID)).thenReturn(USERNAME);
    }

    /**
     * Built the way Spring builds a real CONNECT frame — mutable — because that
     * is what lets an interceptor set the principal on it. A message assembled
     * without {@code setLeaveMutable} is already sealed, and the assignment
     * would fail here for a reason that has nothing to do with authentication.
     */
    private Message<byte[]> connect(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private void givenUser(boolean enabled) {
        UserDetails details = mock(UserDetails.class);
        when(details.getUsername()).thenReturn(USERNAME);
        when(details.isEnabled()).thenReturn(enabled);
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(details);
    }

    @Test
    @DisplayName("a valid token gives the session a principal")
    void authenticatesValidToken() {
        givenUser(true);

        Message<?> result = interceptor.preSend(connect("Bearer " + VALID), mock());

        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(accessor).isNotNull();
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo(USERNAME);
    }

    @Test
    @DisplayName("no Authorization header is refused, not admitted as anonymous")
    void refusesMissingHeader() {
        assertThatThrownBy(() -> interceptor.preSend(connect(null), mock()))
                .isInstanceOf(StompAuthenticationException.class);
    }

    @Test
    @DisplayName("a token the server does not recognise is refused")
    void refusesInvalidToken() {
        when(jwtService.isAccessTokenValid("nope")).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preSend(connect("Bearer nope"), mock()))
                .isInstanceOf(StompAuthenticationException.class);
    }

    @Test
    @DisplayName("an account that stopped being usable is refused, however valid its token")
    void refusesDisabledAccount() {
        givenUser(false);

        assertThatThrownBy(() -> interceptor.preSend(connect("Bearer " + VALID), mock()))
                .isInstanceOf(StompAuthenticationException.class);
    }

    @Test
    @DisplayName("a token naming a user who no longer exists is refused")
    void refusesUnknownUser() {
        when(userDetailsService.loadUserByUsername(anyString()))
                .thenThrow(new UsernameNotFoundException(USERNAME));

        assertThatThrownBy(() -> interceptor.preSend(connect("Bearer " + VALID), mock()))
                .isInstanceOf(StompAuthenticationException.class);
    }

    @Test
    @DisplayName("frames other than CONNECT pass through untouched")
    void leavesOtherFramesAlone() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(message, mock())).isSameAs(message);
    }
}
