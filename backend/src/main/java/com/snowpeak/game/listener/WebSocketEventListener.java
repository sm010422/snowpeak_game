package com.snowpeak.game.listener;

import com.snowpeak.game.service.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final GameService gameService;

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        
        // 세션에서 닉네임 꺼내기 (Controller에서 저장해둔 것)
        String nickname = (String) headerAccessor.getSessionAttributes().get("nickname");

        if (nickname != null) {
            System.out.println("User Disconnected: " + nickname);
            
            // 서비스에게 삭제 요청!
            gameService.removePlayer(nickname);
        }
    }
}
