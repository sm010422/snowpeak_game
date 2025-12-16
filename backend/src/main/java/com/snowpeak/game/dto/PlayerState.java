package com.snowpeak.game.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerState {

    private String playerId;
    private double x;
    private double y;
    private String direction;
    private Role role;
    private AnimState animState;
    private String roomId;

    public enum Role {
        SERVER,
        BARISTA
    }

    public enum AnimState {
        IDLE,
        WALK
    }
}
