package com.p2picks.app.delivery;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.messaging.web.csrf.XorCsrfChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over a plain WebSocket at {@code /ws}. The HTTP handshake goes through the normal security filter chain
 * (session cookie, active player) and must come from the allowed origin; CONNECT must carry the CSRF token.
 * Only per-user destinations exist, and there are no application destinations clients could send to.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final String allowedOrigin;
    private final SubscriptionGuard guard;
    private final RealtimeSessions sessions;
    private final TaskScheduler heartbeats;
    private final CsrfHandshake csrfHandshake;

    WebSocketConfiguration(@Value("${PVPICKS_DEV_ORIGIN:http://127.0.0.1:8080}") String allowedOrigin,
            SubscriptionGuard guard, RealtimeSessions sessions,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler heartbeats, CsrfHandshake csrfHandshake) {
        this.allowedOrigin = allowedOrigin;
        this.csrfHandshake = csrfHandshake;
        this.guard = guard;
        this.sessions = sessions;
        this.heartbeats = heartbeats;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigin).addInterceptors(csrfHandshake);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue").setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(heartbeats);
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new XorCsrfChannelInterceptor(), guard);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessions::decorate).setMessageSizeLimit(16 * 1024);
    }
}
