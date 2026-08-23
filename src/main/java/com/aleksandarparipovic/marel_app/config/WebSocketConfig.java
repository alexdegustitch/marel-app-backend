package com.aleksandarparipovic.marel_app.config;

import com.aleksandarparipovic.marel_app.auth.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // /topic is the broadcast half (report recalculation); /queue is the
        // per-user half, which only means anything because CONNECT is
        // authenticated and every session therefore has a principal.
        config.enableSimpleBroker("/topic", "/queue");
        // Prefix for messages from clients to @MessageMapping methods (if needed)
        config.setApplicationDestinationPrefixes("/app");
        // The server sends to "/user/{username}/queue/..."; the client subscribes
        // to "/user/queue/..." and the broker resolves it to its own session.
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
    }

    /**
     * Every inbound frame passes the authentication interceptor, which refuses a
     * CONNECT without a valid access token. Origin patterns stay permissive on
     * purpose: the desktop build has no useful origin of its own, so the token —
     * not the origin — is what decides who may connect.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
