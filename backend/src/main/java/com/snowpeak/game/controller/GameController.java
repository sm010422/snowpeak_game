package com.snowpeak.game.controller;

import com.snowpeak.game.dto.GameMessage;
import com.snowpeak.game.dto.MoveRequest;
import com.snowpeak.game.dto.PingRequest;
import com.snowpeak.game.service.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;

    @MessageMapping("/join")
    public void handleJoin(@Payload GameMessage message, SimpMessageHeaderAccessor headerAccessor) {
        gameService.joinPlayer(message, headerAccessor.getSessionId(), headerAccessor.getSessionAttributes());
    }

    @MessageMapping("/move")
    public void handleMove(@Payload MoveRequest move, SimpMessageHeaderAccessor headerAccessor) {
        gameService.movePlayer(move, headerAccessor.getSessionAttributes());
    }

    // RTT 측정용 - 받은 시각을 그대로 돌려준다
    @MessageMapping("/ping")
    public void handlePing(@Payload PingRequest ping, SimpMessageHeaderAccessor headerAccessor) {
        gameService.pong(headerAccessor.getSessionId(), ping.getSentAt());
    }
}
