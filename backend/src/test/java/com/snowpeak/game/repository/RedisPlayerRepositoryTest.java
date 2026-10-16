package com.snowpeak.game.repository;

import com.snowpeak.game.dto.PlayerState;
import com.snowpeak.game.global.config.RedisConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// 로컬 Redis(localhost:6379)가 떠 있을 때만 실행 - Lua 스크립트와 직렬화 형식을 실제 Redis 로 검증
class RedisPlayerRepositoryTest {

    private static final String ROOM = "test-" + System.nanoTime();

    private LettuceConnectionFactory connectionFactory;
    private RedisPlayerRepository repository;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        boolean redisUp;
        try (var connection = connectionFactory.getConnection()) {
            redisUp = "PONG".equals(connection.ping());
        } catch (Exception e) {
            redisUp = false;
        }
        assumeTrue(redisUp, "Redis is not running on localhost:6379");

        var template = new RedisConfig().redisTemplate(connectionFactory);
        template.afterPropertiesSet();
        repository = new RedisPlayerRepository(template);
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.delete(ROOM, "alice");
            repository.delete(ROOM, "bob");
        }
        connectionFactory.destroy();
    }

    private static PlayerState player(String id, double x) {
        return new PlayerState(id, x, 0, "down", PlayerState.Role.SERVER, PlayerState.AnimState.IDLE, ROOM, "JOIN");
    }

    @Test
    void saveIfAbsentRejectsDuplicateNickname() {
        assertThat(repository.saveIfAbsent(player("alice", 1))).isTrue();
        assertThat(repository.saveIfAbsent(player("alice", 2))).isFalse();
        assertThat(repository.findAll(ROOM)).singleElement().extracting(PlayerState::getX).isEqualTo(1.0);
    }

    @Test
    void updateAllIfPresentOnlyTouchesExistingPlayersAndStaysReadable() {
        repository.saveIfAbsent(player("alice", 1));

        int updated = repository.updateAllIfPresent(ROOM, List.of(player("alice", 99), player("bob", 5)));

        assertThat(updated).isEqualTo(1);
        // Lua 로 저장한 값도 기존 HSET 값과 같은 형식으로 역직렬화되어야 한다
        assertThat(repository.findAll(ROOM)).singleElement().satisfies(p -> {
            assertThat(p.getPlayerId()).isEqualTo("alice");
            assertThat(p.getX()).isEqualTo(99.0);
        });
    }

    @Test
    void deleteReportsWhetherPlayerExisted() {
        repository.saveIfAbsent(player("alice", 1));
        assertThat(repository.delete(ROOM, "alice")).isTrue();
        assertThat(repository.delete(ROOM, "alice")).isFalse();
    }
}
