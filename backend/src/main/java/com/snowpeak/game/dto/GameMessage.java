package com.snowpeak.game.dto;

import com.snowpeak.game.dto.PlayerState.Role; // PlayerState의 Enum 재사용
import com.snowpeak.game.dto.PlayerState.AnimState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GameMessage {
    private MessageType type;
    private String roomId;    // 방 번호가 메시지에 포함되어야 라우팅이 쉬울 수 있음
    private String nickname;
    
    // String 대신 Enum을 직접 사용하면 JSON 파싱 시 자동 매핑됨 (물론 String으로 받고 변환해도 됩니다)
    private Role role;       
    
    private double x;
    private double y;
    
    // 움직임 동기화를 위한 필드 추가
    private String direction; 
    private AnimState animState; 

    public enum MessageType {
        JOIN, MOVE, LEAVE, STATE_UPDATE // STATE_UPDATE는 서버가 클라에 뿌릴 때 사용 가능
    }
}
