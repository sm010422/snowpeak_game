package com.snowpeak.game.dto;

import java.util.List;

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

    private List<PlayerState> players;

    public enum MessageType {
        JOIN, MOVE, LEAVE, SYNC
    }
}
