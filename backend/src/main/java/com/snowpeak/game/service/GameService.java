package com.snowpeak.game.service;

import com.snowpeak.game.dto.GameMessage;
import com.snowpeak.game.dto.MoveRequest;
import com.snowpeak.game.dto.PlayerState;
import com.snowpeak.game.dto.ServerMessage;
import com.snowpeak.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    public static final String DEFAULT_ROOM_ID = "1";
    public static final String ATTR_NICKNAME = "nickname";
    public static final String ATTR_ROOM_ID = "roomId";
    public static final String ATTR_ROLE = "role";

    private static final int MAX_NICKNAME_LENGTH = 16;
    private static final double MAP_WIDTH = 800;
    private static final double MAP_HEIGHT = 600;

    private final SimpMessagingTemplate messagingTemplate;
    private final PlayerRepository playerRepository;

    // 아직 Redis 에 저장하지 않은 최신 위치 (roomId → nickname → state)
    // 이동은 즉시 브로드캐스트하고, Redis 저장은 flushPendingMoves() 가 모아서 처리한다.
    private final Map<String, Map<String, PlayerState>> pendingMoves = new ConcurrentHashMap<>();

    // 1. 플레이어 입장 처리
    public void joinPlayer(GameMessage message, String sessionId, Map<String, Object> sessionAttributes) {
        String nickname = message.getNickname() == null ? "" : message.getNickname().trim();
        if (nickname.isEmpty() || nickname.length() > MAX_NICKNAME_LENGTH) {
            sendError(sessionId, "INVALID_NICKNAME", "Nickname must be 1-" + MAX_NICKNAME_LENGTH + " characters");
            return;
        }

        Optional<PlayerState.Role> role = PlayerState.Role.from(message.getRole());
        if (role.isEmpty()) {
            sendError(sessionId, "INVALID_ROLE", "Unknown role: " + message.getRole());
            return;
        }

        String roomId = isBlank(message.getRoomId()) ? DEFAULT_ROOM_ID : message.getRoomId();
        PlayerState playerState = new PlayerState(
                nickname,
                clamp(message.getX(), MAP_WIDTH),
                clamp(message.getY(), MAP_HEIGHT),
                "down",
                role.get(),
                PlayerState.AnimState.IDLE,
                roomId,
                "JOIN"
        );

        // HSETNX: 같은 닉네임이 이미 있으면 덮어쓰지 않고 거절
        if (!playerRepository.saveIfAbsent(playerState)) {
            sendError(sessionId, "NICKNAME_TAKEN", "Nickname already in use: " + nickname);
            return;
        }

        // 이후 이동/퇴장 메시지는 클라이언트 값이 아니라 이 세션 정보로 처리한다
        sessionAttributes.put(ATTR_NICKNAME, nickname);
        sessionAttributes.put(ATTR_ROOM_ID, roomId);
        sessionAttributes.put(ATTR_ROLE, role.get());

        // 새로 온 사람에게 현재 방 전체 상태를 한 번에 전달 (본인 세션에만)
        sendToSession(sessionId, "/queue/sync", ServerMessage.of(ServerMessage.Type.ROOM_STATE, getRoomPlayers(roomId)));

        // 다른 사람들에게 입장 알림
        broadcast(roomId, ServerMessage.of(ServerMessage.Type.PLAYER_JOINED, playerState));
    }

    // 2. 플레이어 이동 처리 - Redis 를 기다리지 않고 바로 브로드캐스트
    public void movePlayer(MoveRequest move, Map<String, Object> sessionAttributes) {
        String nickname = (String) sessionAttributes.get(ATTR_NICKNAME);
        String roomId = (String) sessionAttributes.get(ATTR_ROOM_ID);
        if (nickname == null || roomId == null) return; // 입장하지 않은 세션

        PlayerState playerState = new PlayerState(
                nickname,
                clamp(move.getX(), MAP_WIDTH),
                clamp(move.getY(), MAP_HEIGHT),
                move.getDirection(),
                (PlayerState.Role) sessionAttributes.get(ATTR_ROLE),
                move.getAnimState() != null ? move.getAnimState() : PlayerState.AnimState.WALK,
                roomId,
                "MOVE"
        );

        broadcast(roomId, ServerMessage.of(ServerMessage.Type.MOVE, playerState, move.getSentAt()));
        pendingMoves.computeIfAbsent(roomId, id -> new ConcurrentHashMap<>()).put(nickname, playerState);
    }

    // 3. 플레이어 퇴장 처리 (접속 끊김 시 호출)
    public void removePlayer(Map<String, Object> sessionAttributes) {
        String nickname = (String) sessionAttributes.get(ATTR_NICKNAME);
        String roomId = (String) sessionAttributes.get(ATTR_ROOM_ID);
        if (nickname == null || roomId == null) return;

        Map<String, PlayerState> pending = pendingMoves.get(roomId);
        if (pending != null) pending.remove(nickname);

        if (playerRepository.delete(roomId, nickname)) {
            broadcast(roomId, ServerMessage.of(ServerMessage.Type.PLAYER_LEFT, Map.of("playerId", nickname)));
            log.info("Player removed & broadcasted: {} (room {})", nickname, roomId);
        }
    }

    // 4. 쌓인 최신 위치를 방 단위로 한 번에 Redis 에 저장 (이동 1번마다 HSET 하던 것을 묶음 처리)
    @Scheduled(fixedDelayString = "${snowpeak.sync.flush-interval-ms:100}")
    public void flushPendingMoves() {
        pendingMoves.forEach((roomId, moves) -> {
            if (moves.isEmpty()) return;
            Map<String, PlayerState> batch = new HashMap<>(moves);
            try {
                playerRepository.updateAllIfPresent(roomId, batch.values());
                // 저장하는 사이 더 새로운 위치가 들어왔다면 지우지 않고 다음 flush 에 저장
                batch.forEach(moves::remove);
            } catch (Exception e) {
                log.warn("Failed to flush {} moves for room {}: {}", batch.size(), roomId, e.getMessage());
            }
        });
    }

    // Redis 에 저장된 상태 + 아직 저장되지 않은 최신 위치
    public List<PlayerState> getRoomPlayers(String roomId) {
        Map<String, PlayerState> players = new LinkedHashMap<>();
        playerRepository.findAll(roomId).forEach(p -> players.put(p.getPlayerId(), p));

        Map<String, PlayerState> pending = pendingMoves.get(roomId);
        if (pending != null) {
            pending.forEach((nickname, latest) -> players.computeIfPresent(nickname, (k, v) -> latest));
        }
        return new ArrayList<>(players.values());
    }

    public void pong(String sessionId, Long sentAt) {
        sendToSession(sessionId, "/queue/pong", ServerMessage.of(ServerMessage.Type.PONG, null, sentAt));
    }

    private void broadcast(String roomId, ServerMessage message) {
        messagingTemplate.convertAndSend("/topic/room." + roomId, message);
    }

    // Principal 이 없는 세션에 1:1 전송 - 세션 ID 헤더가 있어야 /user/queue/** 로 전달된다
    private void sendToSession(String sessionId, String destination, ServerMessage message) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(sessionId, destination, message, headers.getMessageHeaders());
    }

    private void sendError(String sessionId, String code, String message) {
        sendToSession(sessionId, "/queue/errors", ServerMessage.error(code, message));
    }

    private static double clamp(double value, double max) {
        return Math.max(0, Math.min(max, value));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
