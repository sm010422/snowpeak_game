package com.snowpeak.game.repository;

import com.snowpeak.game.dto.PlayerState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Redis 없이 테스트하기 위한 PlayerRepository 구현
public class InMemoryPlayerRepository implements PlayerRepository {

    private final Map<String, Map<String, PlayerState>> rooms = new ConcurrentHashMap<>();

    private Map<String, PlayerState> room(String roomId) {
        return rooms.computeIfAbsent(roomId, id -> new ConcurrentHashMap<>());
    }

    @Override
    public boolean saveIfAbsent(PlayerState state) {
        return room(state.getRoomId()).putIfAbsent(state.getPlayerId(), state) == null;
    }

    @Override
    public List<PlayerState> findAll(String roomId) {
        return new ArrayList<>(room(roomId).values());
    }

    @Override
    public synchronized int updateAllIfPresent(String roomId, Collection<PlayerState> states) {
        int updated = 0;
        for (PlayerState state : states) {
            if (room(roomId).replace(state.getPlayerId(), state) != null) updated++;
        }
        return updated;
    }

    @Override
    public boolean delete(String roomId, String playerId) {
        return room(roomId).remove(playerId) != null;
    }

    @Override
    public void deleteAllRooms() {
        rooms.clear();
    }
}
