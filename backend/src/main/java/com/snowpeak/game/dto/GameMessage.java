package com.snowpeak.game.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// 클라이언트 → 서버 입장 요청 (/app/join)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GameMessage {
    private String roomId;
    private String nickname;
    private String role;
    private double x;
    private double y;
}
