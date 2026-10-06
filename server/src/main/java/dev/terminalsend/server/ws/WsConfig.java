package dev.terminalsend.server.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
class WsConfig implements WebSocketConfigurer {

    /** 64 KiB of ciphertext is ~87 KiB in Base64, plus the JSON envelope. Tomcat's default is only 8 KiB. */
    static final int MAX_TEXT_FRAME_BYTES = 128 * 1024;

    private final WsHandler handler;
    private final WsAuthInterceptor auth;

    WsConfig(WsHandler handler, WsAuthInterceptor auth) {
        this.handler = handler;
        this.auth = auth;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Terminal clients send no Origin header; authentication is the bearer token, not the origin.
        registry.addHandler(handler, "/ws").addInterceptors(auth).setAllowedOriginPatterns("*");
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_FRAME_BYTES);
        return container;
    }
}
