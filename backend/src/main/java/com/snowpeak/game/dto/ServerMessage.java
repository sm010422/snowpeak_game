package com.snowpeak.game.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

// 서버 → 클라이언트 공통 메시지 형식: { type, payload, serverTime, sentAt }
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerMessage {
    private Type type;
    private Object payload;
    private long serverTime;
    private Long sentAt; // MOVE / PONG 에서 원본 전송 시각을 그대로 돌려줌

    public enum Type {
        ROOM_STATE, PLAYER_JOINED, MOVE, PLAYER_LEFT, PONG, ERROR
    }

    public static ServerMessage of(Type type, Object payload) {
        return new ServerMessage(type, payload, System.currentTimeMillis(), null);
    }

    public static ServerMessage of(Type type, Object payload, Long sentAt) {
        return new ServerMessage(type, payload, System.currentTimeMillis(), sentAt);
    }

    public static ServerMessage error(String code, String message) {
        return of(Type.ERROR, Map.of("code", code, "message", message));
    }
}
