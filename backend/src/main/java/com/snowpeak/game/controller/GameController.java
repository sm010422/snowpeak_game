package com.snowpeak.game.controller;

import com.snowpeak.game.dto.PlayerState;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class GameController {

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, String, PlayerState> hashOperations;

    @Autowired
    public GameController(SimpMessagingTemplate messagingTemplate, RedisTemplate<String, Object> redisTemplate) {
        this.messagingTemplate = messagingTemplate;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    private void init() {
        hashOperations = redisTemplate.opsForHash();
    }

    private static final String PLAYER_KEY_PREFIX = "room:";

    @MessageMapping("/move")
    public void handleMove(PlayerState playerState) {
        String roomId = playerState.getRoomId();
        if (isInvalid(roomId)) return;

        String key = PLAYER_KEY_PREFIX + roomId;
        hashOperations.put(key, playerState.getPlayerId(), playerState);

        String destination = "/topic/room." + roomId;
        messagingTemplate.convertAndSend(destination, playerState);
    }

    @MessageMapping("/join")
    public void joinRoom(PlayerState playerState) {
        String roomId = playerState.getRoomId();
        if (isInvalid(roomId)) return;

        String key = PLAYER_KEY_PREFIX + roomId;
        hashOperations.put(key, playerState.getPlayerId(), playerState);

        String destination = "/topic/room." + roomId;
        messagingTemplate.convertAndSend(destination, playerState);
    }

    private boolean isInvalid(String roomId) {
        return roomId == null || roomId.trim().isEmpty();
    }
}
