package com.snowpeak.game.controller;

import com.snowpeak.game.dto.GameMessage;
import com.snowpeak.game.dto.PlayerState;
import com.snowpeak.game.service.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate; // 추가됨
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate; // ★ 메시지 발송 도구 추가

    @MessageMapping("/join")
    public void handleJoin(@Payload GameMessage message, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId(); // 세션 ID 확보
        
        // 1. 세션에 닉네임 저장
        headerAccessor.getSessionAttributes().put("nickname", message.getNickname());

        // 2. 서비스에게 로직 위임 (기존 유저들에게 "나 왔어!" 알림은 여기서 처리됨)
        gameService.joinPlayer(message, sessionId);

        // =================================================================
        // ▼▼▼ [추가된 로직] 입장한 사람에게 "현재 접속자 명단" 보내주기 ▼▼▼
        // =================================================================
        
        // 3. 현재 방에 있는 모든 플레이어 목록 가져오기 (Service에 이 메소드가 필요함)
        List<PlayerState> currentPlayers = gameService.getAllPlayers(); // 혹은 getAllPlayersInRoom(roomId)

        // 4. 동기화(SYNC) 메시지 생성
        GameMessage syncMessage = new GameMessage();
        syncMessage.setType(GameMessage.MessageType.SYNC); // 클라이언트가 구분할 수 있게 타입을 'SYNC'로 설정
        syncMessage.setPlayers(currentPlayers); // 리스트 담기

        // 5. 이 사람(sessionId)의 개인 채널로 전송
        // 클라이언트가 '/topic/private/{sessionId}'를 구독하고 있어야 함
        messagingTemplate.convertAndSend("/topic/private/" + message.getNickname(), syncMessage);
    }

    @MessageMapping("/update")
    public void handleUpdate(@Payload PlayerState playerState) {
        gameService.updatePlayer(playerState);
    }
}
