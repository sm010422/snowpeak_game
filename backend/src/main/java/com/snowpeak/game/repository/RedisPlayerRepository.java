package com.snowpeak.game.repository;

import com.snowpeak.game.dto.PlayerState;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class RedisPlayerRepository implements PlayerRepository {

    private static final String KEY_PREFIX = "room:";

    // HEXISTS 확인 후 HSET 을 한 번에 실행 → 퇴장(HDEL)과 위치 저장이 겹쳐도 유령 플레이어가 생기지 않음
    private static final RedisScript<Long> UPDATE_IF_PRESENT = new DefaultRedisScript<>("""
            local updated = 0
            for i = 1, #ARGV, 2 do
              if redis.call('HEXISTS', KEYS[1], ARGV[i]) == 1 then
                redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
                updated = updated + 1
              end
            end
            return updated
            """, Long.class);

    private final RedisTemplate<String, Object> redisTemplate;

    private HashOperations<String, String, PlayerState> hash() {
        return redisTemplate.opsForHash();
    }

    private static String key(String roomId) {
        return KEY_PREFIX + roomId;
    }

    @Override
    public boolean saveIfAbsent(PlayerState state) {
        return Boolean.TRUE.equals(hash().putIfAbsent(key(state.getRoomId()), state.getPlayerId(), state));
    }

    @Override
    public List<PlayerState> findAll(String roomId) {
        List<PlayerState> players = hash().values(key(roomId));
        return players == null ? new ArrayList<>() : players;
    }

    @Override
    @SuppressWarnings("unchecked")
    public int updateAllIfPresent(String roomId, Collection<PlayerState> states) {
        if (states.isEmpty()) return 0;

        // Lua 인자는 문자열로 넘기므로, 해시 값 직렬화(JSON)를 미리 적용해 HSET 과 같은 형식으로 저장
        RedisSerializer<Object> valueSerializer = (RedisSerializer<Object>) redisTemplate.getHashValueSerializer();
        Object[] args = new Object[states.size() * 2];
        int i = 0;
        for (PlayerState state : states) {
            args[i++] = state.getPlayerId();
            args[i++] = new String(valueSerializer.serialize(state), StandardCharsets.UTF_8);
        }

        Long updated = redisTemplate.execute(UPDATE_IF_PRESENT, RedisSerializer.string(),
                new GenericToStringSerializer<>(Long.class), List.of(key(roomId)), args);
        return updated == null ? 0 : updated.intValue();
    }

    @Override
    public boolean delete(String roomId, String playerId) {
        Long removed = hash().delete(key(roomId), playerId);
        return removed != null && removed > 0;
    }

    @Override
    public void deleteAllRooms() {
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build();
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
