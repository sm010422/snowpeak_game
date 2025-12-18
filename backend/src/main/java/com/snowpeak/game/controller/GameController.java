package com.snowpeak.game.controller;

import com.snowpeak.game.dto.GameMessage;
import com.snowpeak.game.dto.PlayerState;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class GameController {

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, String, PlayerState> hashOperations;

    private static final String ROOM_ID = "1";
    private static final String PLAYER_KEY_PREFIX = "room:";

    @Autowired
    public GameController(SimpMessagingTemplate messagingTemplate, RedisTemplate<String, Object> redisTemplate) {
        this.messagingTemplate = messagingTemplate;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    private void init() {
        hashOperations = redisTemplate.opsForHash();
    }

    @MessageMapping("/join")
    public void handleJoin(@Payload GameMessage message, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        headerAccessor.getSessionAttributes().put("nickname", message.getNickname());

        PlayerState playerState = new PlayerState(
                message.getNickname(),
                message.getX(),
                message.getY(),
                "down", // Default direction
                PlayerState.Role.valueOf(message.getRole().toUpperCase()),
                PlayerState.AnimState.IDLE, // Default anim state
                ROOM_ID
        );

        String key = PLAYER_KEY_PREFIX + ROOM_ID;
        hashOperations.put(key, playerState.getPlayerId(), playerState);

        // Broadcast to all clients in the room
        String destination = "/topic/room." + ROOM_ID;
        messagingTemplate.convertAndSend(destination, playerState);

        // Send existing players to the new player
        Map<String, PlayerState> players = hashOperations.entries(key);
        players.values().forEach(ps -> {
            if (!ps.getPlayerId().equals(message.getNickname())) {
                messagingTemplate.convertAndSendToUser(sessionId, "/queue/players", ps);
            }
        });
    }

    @MessageMapping("/update")
    public void handleUpdate(@Payload PlayerState playerState) {
        String key = PLAYER_KEY_PREFIX + playerState.getRoomId();
        hashOperations.put(key, playerState.getPlayerId(), playerState);

        String destination = "/topic/room." + playerState.getRoomId();
        messagingTemplate.convertAndSend(destination, playerState);
    }
}
