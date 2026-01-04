package com.snowpeak.game.controller;

import com.snowpeak.game.dto.GameMessage;
import com.snowpeak.game.dto.PlayerState;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
@RequiredArgsConstructor
public class GameController {

    private final SimpMessagingTemplate template;
    
    // 서버 메모리에 플레이어 상태 저장 (Key: nickname, Value: PlayerState)
    // 실제 서비스에서는 DB나 Redis, 혹은 별도 Service 클래스로 관리하는 것이 좋습니다.
    private static final Map<String, PlayerState> gameStore = new ConcurrentHashMap<>();

    // 클라이언트가 "/app/game/message"로 보낸 메시지를 처리
    @MessageMapping("/game/message")
    public void handleGameMessage(@Payload GameMessage message) {
        
        // 1. 메시지 타입에 따른 처리
        switch (message.getType()) {
            case JOIN:
                // 입장 시 초기 상태 생성 및 저장
                PlayerState newState = new PlayerState(
                        message.getNickname(),
                        message.getX(),
                        message.getY(),
                        message.getDirection(),
                        message.getRole(),
                        PlayerState.AnimState.IDLE, // 처음엔 멈춤 상태
                        message.getRoomId(),
                        "ACTIVE"
                );
                gameStore.put(message.getNickname(), newState);
                
                // (선택) 입장 시, 이미 방에 있던 사람들의 정보를 이 사람에게 따로 보내주는 로직이 필요할 수 있음
                break;

            case MOVE:
                // 이동 시 해당 플레이어 상태 업데이트
                if (gameStore.containsKey(message.getNickname())) {
                    PlayerState player = gameStore.get(message.getNickname());
                    player.setX(message.getX());
                    player.setY(message.getY());
                    player.setDirection(message.getDirection());
                    player.setAnimState(message.getAnimState());
                    // 갱신된 정보 다시 저장 (필요 시)
                    gameStore.put(message.getNickname(), player);
                }
                break;

            case LEAVE:
                // 퇴장 시 저장소에서 제거
                gameStore.remove(message.getNickname());
                break;
        }

        // 2. 같은 방(roomId)에 있는 모든 사람에게 상태 변경 사항 전송 (브로드캐스트)
        // 구독 경로 예시: /topic/room/1
        template.convertAndSend("/topic/room/" + message.getRoomId(), message);
    }
}
