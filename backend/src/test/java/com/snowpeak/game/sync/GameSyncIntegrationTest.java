package com.snowpeak.game.sync;

import com.snowpeak.game.dto.PlayerState;
import com.snowpeak.game.repository.InMemoryPlayerRepository;
import com.snowpeak.game.repository.PlayerRepository;
import com.snowpeak.game.service.GameService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "snowpeak.sync.flush-interval-ms=50")
class GameSyncIntegrationTest {

    @TestConfiguration
    static class InMemoryStoreConfig {
        @Bean
        @Primary
        PlayerRepository inMemoryPlayerRepository() {
            return new InMemoryPlayerRepository();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    PlayerRepository playerRepository;

    @Autowired
    GameService gameService;

    private WebSocketStompClient stompClient;
    private final List<StompSession> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        playerRepository.deleteAllRooms();
        stompClient = new WebSocketStompClient(
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
        stompClient.stop();
    }

    @Test
    void lateJoinerReceivesRoomStateWithExistingPlayers() throws Exception {
        Client alice = connect();
        alice.join("alice", "server");
        alice.awaitType(alice.room, "PLAYER_JOINED");

        Client bob = connect();
        bob.join("bob", "cook");

        Map<String, Object> sync = bob.awaitType(bob.sync, "ROOM_STATE");
        assertThat(playerIds(sync)).containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void moveIsBroadcastWithServerSideIdentityAndSentAt() throws Exception {
        Client alice = connect();
        alice.join("alice", "server");
        Client bob = connect();
        bob.join("bob", "cook");
        bob.awaitType(bob.sync, "ROOM_STATE");

        // 클라이언트는 자기 ID 를 보내지 않는다 - 서버가 세션으로 식별
        alice.session.send("/app/move", Map.of("x", 123, "y", 456, "direction", "left", "sentAt", 1000L));

        Map<String, Object> move = bob.awaitType(bob.room, "MOVE");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) move.get("payload");
        assertThat(payload.get("playerId")).isEqualTo("alice");
        assertThat(payload.get("x")).isEqualTo(123.0);
        assertThat(payload.get("roomId")).isEqualTo("1");
        assertThat(((Number) move.get("sentAt")).longValue()).isEqualTo(1000L);
    }

    @Test
    void latestPositionIsFlushedToStoreAndIncludedInSync() throws Exception {
        Client alice = connect();
        alice.join("alice", "server");
        alice.awaitType(alice.sync, "ROOM_STATE");

        // 같은 세션에서 연속으로 보낸 이동은 보낸 순서대로 처리되어 마지막 위치가 남아야 한다
        for (int x = 1; x <= 29; x++) {
            alice.session.send("/app/move", Map.of("x", x, "y", 20, "direction", "up"));
        }
        alice.session.send("/app/move", Map.of("x", 30, "y", 40, "direction", "up"));

        awaitCondition(() -> playerRepository.findAll("1").stream()
                .anyMatch(p -> p.getX() == 30 && p.getY() == 40));

        Client bob = connect();
        bob.join("bob", "cook");
        Map<String, Object> sync = bob.awaitType(bob.sync, "ROOM_STATE");
        assertThat(sync.toString()).contains("x=30.0").contains("y=40.0");
    }

    @Test
    void disconnectBroadcastsPlayerLeftAndRemovesPlayer() throws Exception {
        Client alice = connect();
        alice.join("alice", "server");
        Client bob = connect();
        bob.join("bob", "cook");
        bob.awaitType(bob.sync, "ROOM_STATE");

        alice.session.disconnect();

        Map<String, Object> left = bob.awaitType(bob.room, "PLAYER_LEFT");
        assertThat(left.toString()).contains("playerId=alice");
        awaitCondition(() -> playerRepository.findAll("1").stream().noneMatch(p -> p.getPlayerId().equals("alice")));
    }

    @Test
    void duplicateNicknameIsRejectedWithoutOverwriting() throws Exception {
        Client first = connect();
        first.join("alice", "server");
        first.awaitType(first.sync, "ROOM_STATE");

        Client second = connect();
        second.join("alice", "cook");

        Map<String, Object> error = second.awaitType(second.errors, "ERROR");
        assertThat(error.toString()).contains("NICKNAME_TAKEN");
        assertThat(playerRepository.findAll("1")).singleElement()
                .extracting(PlayerState::getRole).isEqualTo(PlayerState.Role.SERVER);
    }

    @Test
    void unknownRoleIsRejectedInsteadOfThrowing() throws Exception {
        Client client = connect();
        client.join("alice", "pilot");

        Map<String, Object> error = client.awaitType(client.errors, "ERROR");
        assertThat(error.toString()).contains("INVALID_ROLE");
        assertThat(playerRepository.findAll("1")).isEmpty();
    }

    @Test
    void pingIsAnsweredWithOriginalTimestamp() throws Exception {
        Client client = connect();
        client.session.send("/app/ping", Map.of("sentAt", 42L));

        Map<String, Object> pong = client.awaitType(client.pong, "PONG");
        assertThat(((Number) pong.get("sentAt")).longValue()).isEqualTo(42L);
    }

    @Test
    void flushAfterLeaveDoesNotResurrectPlayer() {
        Map<String, Object> attrs = new java.util.concurrent.ConcurrentHashMap<>();
        attrs.put(GameService.ATTR_NICKNAME, "ghost");
        attrs.put(GameService.ATTR_ROOM_ID, "1");
        attrs.put(GameService.ATTR_ROLE, PlayerState.Role.SERVER);
        playerRepository.saveIfAbsent(new PlayerState("ghost", 0, 0, "down", PlayerState.Role.SERVER,
                PlayerState.AnimState.IDLE, "1", "JOIN"));

        // 퇴장 직후 늦게 도착한 이동 메시지가 pending 에 남은 상황
        gameService.removePlayer(attrs);
        gameService.movePlayer(new com.snowpeak.game.dto.MoveRequest(5, 5, "up", null, null), attrs);
        gameService.flushPendingMoves();

        assertThat(playerRepository.findAll("1")).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private Client connect() throws Exception {
        StompSession session = stompClient
                .connectAsync("http://localhost:" + port + "/ws-snowpeak", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        sessions.add(session);
        Client client = new Client(session);
        client.subscribeAll();
        return client;
    }

    @SuppressWarnings("unchecked")
    private static List<String> playerIds(Map<String, Object> message) {
        return ((List<Map<String, Object>>) message.get("payload")).stream()
                .map(p -> (String) p.get("playerId")).toList();
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).as("condition within 3s").isTrue();
    }

    static class Client {
        final StompSession session;
        final BlockingQueue<Map<String, Object>> room = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, Object>> sync = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, Object>> pong = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, Object>> errors = new LinkedBlockingQueue<>();

        Client(StompSession session) {
            this.session = session;
        }

        void subscribeAll() throws InterruptedException {
            subscribe("/topic/room.1", room);
            subscribe("/user/queue/sync", sync);
            subscribe("/user/queue/pong", pong);
            subscribe("/user/queue/errors", errors);
            Thread.sleep(100); // SUBSCRIBE 프레임이 서버에 등록될 시간
        }

        void join(String nickname, String role) {
            session.send("/app/join", Map.of("nickname", nickname, "role", role, "x", 400, "y", 300));
        }

        private void subscribe(String destination, BlockingQueue<Map<String, Object>> queue) {
            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                @SuppressWarnings("unchecked")
                public void handleFrame(StompHeaders headers, Object payload) {
                    queue.add((Map<String, Object>) payload);
                }
            });
        }

        Map<String, Object> awaitType(BlockingQueue<Map<String, Object>> queue, String type) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                Map<String, Object> message = queue.poll(100, TimeUnit.MILLISECONDS);
                if (message != null && type.equals(message.get("type"))) return message;
            }
            throw new AssertionError("No " + type + " message within 3s");
        }
    }
}
