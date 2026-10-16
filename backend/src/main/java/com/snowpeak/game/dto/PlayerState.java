package com.snowpeak.game.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;
import java.util.Optional;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerState {

    private String playerId; // This will be the nickname
    private double x;
    private double y;
    private String direction;
    private Role role;
    private AnimState animState;
    private String roomId;

    private String status;

    public enum Role {
        SERVER,
        BARISTA,
        HALL_SERVER,
        COOK;

        // 클라이언트가 보낸 문자열("server", "cook")을 안전하게 변환 (없는 값이면 예외 대신 empty)
        public static Optional<Role> from(String value) {
            if (value == null) return Optional.empty();
            try {
                return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    public enum AnimState {
        IDLE,
        WALK
    }
}
