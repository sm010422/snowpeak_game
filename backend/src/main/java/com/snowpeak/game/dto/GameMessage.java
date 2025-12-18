package com.snowpeak.game.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GameMessage {
    private MessageType type;
    private String nickname;
    private String role;
    private double x;
    private double y;

    public enum MessageType {
        JOIN, MOVE, LEAVE
    }
}
