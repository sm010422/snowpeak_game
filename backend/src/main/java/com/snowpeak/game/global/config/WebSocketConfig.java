package com.snowpeak.game.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        // 세션별 발신 순서 보장 - 브로드캐스트가 스레드 풀에서 뒤섞여 나가지 않도록
        config.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-snowpeak")
                .setAllowedOriginPatterns("*")
                .withSockJS();
        // 세션별 수신 순서 보장 - TCP 로 순서대로 와도 inbound 스레드 풀이 병렬 처리하면 이동 순서가 뒤바뀐다
        registry.setPreserveReceiveOrder(true);
    }
}
