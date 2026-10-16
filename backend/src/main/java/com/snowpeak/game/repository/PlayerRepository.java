package com.snowpeak.game.repository;

import com.snowpeak.game.dto.PlayerState;

import java.util.Collection;
import java.util.List;

public interface PlayerRepository {

    // 같은 닉네임이 없을 때만 저장 (원자적)
    boolean saveIfAbsent(PlayerState state);

    List<PlayerState> findAll(String roomId);

    // 아직 방에 남아 있는 플레이어만 갱신 (퇴장한 플레이어를 되살리지 않음)
    int updateAllIfPresent(String roomId, Collection<PlayerState> states);

    boolean delete(String roomId, String playerId);

    void deleteAllRooms();
}
