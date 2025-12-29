package com.snowpeak.game.controller;

import com.snowpeak.game.dto.GameMessage;
import com.snowpeak.game.dto.PlayerState;
import com.snowpeak.game.service.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor // 생성자 주입 자동화 (Lombok)
public class GameController {

    private final GameService gameService;

    @MessageMapping("/join")
    public void handleJoin(@Payload GameMessage message, SimpMessageHeaderAccessor headerAccessor) {
        // 1. 세션에 닉네임 저장 (나중에 끊길 때 쓰려고)
        headerAccessor.getSessionAttributes().put("nickname", message.getNickname());

        // 2. 서비스에게 로직 위임
        gameService.joinPlayer(message, headerAccessor.getSessionId());
    }

    @MessageMapping("/update")
    public void handleUpdate(@Payload PlayerState playerState) {
        // 서비스에게 로직 위임
        gameService.updatePlayer(playerState);
    }
}
