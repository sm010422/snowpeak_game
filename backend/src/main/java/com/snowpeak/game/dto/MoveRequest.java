package com.snowpeak.game.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// 클라이언트 → 서버 이동 요청 (/app/move)
// 누가 / 어느 방인지는 클라이언트 값을 믿지 않고 서버 세션에서 꺼낸다.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MoveRequest {
    private double x;
    private double y;
    private String direction;
    private PlayerState.AnimState animState;
    private Long sentAt; // 클라이언트 전송 시각(ms) - 지연 측정용
}
