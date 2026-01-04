package com.snowpeak.game.service;

import com.snowpeak.game.dto.GameMessage;
import com.snowpeak.game.dto.PlayerState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class GameService {

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, String, PlayerState> hashOperations;

    private static final String ROOM_ID = "1";
    private static final String PLAYER_KEY_PREFIX = "room:";

    @PostConstruct
    private void init() {
        hashOperations = redisTemplate.opsForHash();
    }

    // 1. 플레이어 입장 처리
// 1. 플레이어 입장 처리
    public void joinPlayer(GameMessage message, String sessionId) {
        // 플레이어 상태 생성
        PlayerState playerState = new PlayerState(
                message.getNickname(),
                message.getX(),
                message.getY(),
                // message에 direction이 있다면 그것을 사용, 없다면 "down" 기본값
                message.getDirection() != null ? message.getDirection() : "down", 
                
                // ★ 수정된 부분: valueOf() 제거하고 바로 넣기
                message.getRole(), 
                
                // message에 animState가 있다면 그것을 사용, 없다면 IDLE 기본값
                message.getAnimState() != null ? message.getAnimState() : PlayerState.AnimState.IDLE, 
                
                ROOM_ID,
                "JOIN"
        );

        String key = PLAYER_KEY_PREFIX + ROOM_ID;
        
        // Redis에 저장
        hashOperations.put(key, playerState.getPlayerId(), playerState);

        // 다른 사람들에게 "새 친구 왔어!" 알림
        String destination = "/topic/room." + ROOM_ID;
        messagingTemplate.convertAndSend(destination, playerState);

        // 새로 온 사람에게 "기존에 있던 사람들" 정보 주기
        Map<String, PlayerState> players = hashOperations.entries(key);
        players.values().forEach(ps -> {
            // 나 자신(방금 들어온 사람)은 제외하고 보냄
            if (!ps.getPlayerId().equals(message.getNickname())) {
                messagingTemplate.convertAndSendToUser(sessionId, "/queue/players", ps);
            }
        });
    }

    // 2. 플레이어 이동/상태 업데이트
    public void updatePlayer(PlayerState playerState) {
        String key = PLAYER_KEY_PREFIX + playerState.getRoomId();
        
        // Redis 정보 갱신
        hashOperations.put(key, playerState.getPlayerId(), playerState);

        // 모두에게 위치 알림
        String destination = "/topic/room." + playerState.getRoomId();
        messagingTemplate.convertAndSend(destination, playerState);
    }

    // 3. ★ 플레이어 퇴장 처리 (접속 끊김 시 호출)
    public void removePlayer(String nickname) {
        String key = PLAYER_KEY_PREFIX + ROOM_ID;

        if (hashOperations.hasKey(key, nickname)) {
            // 1. Redis에서 삭제 (이건 하셨죠?)
            hashOperations.delete(key, nickname);
            
            // 2. ★ 추가할 부분: 다른 사람들에게 "쟤 나갔어(LEAVE)"라고 알려주기
            PlayerState leaveMessage = new PlayerState();
            leaveMessage.setPlayerId(nickname);
            leaveMessage.setRole(PlayerState.Role.HALL_SERVER); // dummy
            leaveMessage.setStatus("LEAVE"); // ★ 상태를 LEAVE로 설정 (DTO에 필드 추가 필요할 수 있음)
            
            String destination = "/topic/room." + ROOM_ID;
            messagingTemplate.convertAndSend(destination, leaveMessage);
            
            System.out.println("Player removed & Broadcasted: " + nickname);
        }
    }
}
