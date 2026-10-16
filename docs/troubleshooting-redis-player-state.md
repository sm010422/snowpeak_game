# 트러블슈팅: Redis 기반 플레이어 상태 관리 — 유령 플레이어, 덮어쓰기, 저장 순서 역전

> **프로젝트**: Snowpeak Game — 실시간 멀티플레이 협동 타이쿤 게임
> **스택**: Java 17, Spring Boot 3.5, Spring WebSocket(STOMP + SockJS), Spring Data Redis(Lettuce), Redis 7, JUnit 5
> **관련 문서**: [이동 동기화 트러블슈팅](./troubleshooting-movement-sync.md)

---

## 1. 한 줄 요약

플레이어 상태를 Redis Hash(`room:{roomId}`)에 저장하는 구조에서 **유령 플레이어, 같은 닉네임 덮어쓰기, `room:null` 키 생성, 이동마다 Redis 동기 쓰기, 이동 순서 역전**을 찾아냈습니다.
**HSETNX / Lua Script / 쓰기 지연(Write-Behind) / 세션 기반 식별 / STOMP 수신 순서 보장**으로 해결하고, **통합 테스트 8개와 실제 Redis 테스트 3개**로 검증했습니다.

---

## 2. Redis를 어떻게 쓰고 있었나

```
Key   : room:{roomId}            (Redis Hash)
Field : nickname                 (= playerId)
Value : PlayerState JSON         (GenericJackson2JsonRedisSerializer)
```

| 시점 | 기존 동작 |
| --- | --- |
| 입장 `/app/join` | `HSET room:1 nickname state` 후 `/topic/room.1`로 브로드캐스트 |
| 이동 `/app/update` | **이동 메시지마다** `HSET room:{클라이언트가 보낸 roomId}` 후 브로드캐스트 |
| 퇴장 (연결 끊김) | `HEXISTS` → `HDEL room:1 nickname` → LEAVE 브로드캐스트 |
| 늦은 입장자 동기화 | `HVALS room:1` 결과를 1명씩 `convertAndSendToUser(sessionId, ...)`로 전송 |

---

## 3. 발견한 문제와 원인

### 문제 ① 서버 재시작 후 "유령 플레이어"가 계속 남는다

- **현상**: 서버를 강제로 내렸다가 다시 띄우면, 아무도 접속하지 않았는데 새 입장자의 화면에 예전 플레이어가 보입니다.
- **원인**: 퇴장 처리는 `SessionDisconnectEvent`에만 의존합니다. **서버 프로세스가 죽으면 이 이벤트가 발생하지 않아** Redis의 데이터가 정리되지 않습니다. 키에 TTL도 없어서 **영구히 남습니다.**
- **핵심**: Spring의 Simple Broker는 **세션을 서버 메모리에만** 들고 있습니다. 서버가 새로 뜬 시점에는 살아 있는 세션이 0개이므로, Redis에 남은 플레이어 데이터는 **전부 유령**입니다.

### 문제 ② 같은 닉네임으로 들어오면 기존 플레이어를 덮어쓴다

- **현상**: A가 `alice`로 접속한 상태에서 B도 `alice`로 접속하면, A의 상태가 B로 바뀝니다. 그 뒤 **B가 나가면 A까지 같이 사라집니다.**
- **원인**: 플레이어 키가 닉네임인데 입장 시 `HSET`으로 **무조건 덮어썼습니다.** 존재 여부를 먼저 확인(`HEXISTS`)한 뒤 저장해도, 두 요청이 동시에 오면 둘 다 "없음"으로 판단하는 **경쟁 조건(Race Condition)** 이 남습니다.

### 문제 ③ `room:null` 키가 생기고, 퇴장이 반영되지 않는다

- **현상**: Redis에 `room:null` 키가 생기고, 브로드캐스트가 `/topic/room.null`로 나갑니다.
- **원인**: 이동 처리(`updatePlayer`)는 **클라이언트가 보낸 `roomId`를 그대로 믿었습니다.** 그런데 프론트엔드는 이동 메시지에 `roomId`를 보내지 않았습니다. 반대로 퇴장 처리는 `ROOM_ID = "1"`로 고정돼 있어, **입장·이동·퇴장이 서로 다른 키를 볼 수 있는** 구조였습니다.
- **보안 관점**: `playerId`도 클라이언트 값을 믿었기 때문에, **다른 사람의 캐릭터를 움직이는 요청도 그대로 처리**됐습니다.

### 문제 ④ 이동 메시지마다 Redis에 동기 쓰기

- **원인**: 이동 1번 = `HSET` 1번입니다. 클라이언트가 50ms마다 보내면 **1인당 초당 20번**, 10명이면 **초당 200번** Redis를 호출합니다.
- **영향**: 브로드캐스트 전에 Redis 응답을 기다리므로, **Redis 왕복 시간이 위치 전달 경로(Hot Path)에 그대로 더해집니다.** 그런데 Redis에 저장된 위치는 **새 입장자에게 SYNC를 보낼 때만** 읽힙니다. 1초에 20번 저장할 이유가 없었습니다.

### 문제 ⑤ 늦은 입장자에게 기존 플레이어 정보가 전달되지 않는다

- **원인**: `convertAndSendToUser(sessionId, "/queue/players", ps)`는 **로그인 사용자(Principal)** 기준으로 목적지를 찾습니다. 이 프로젝트는 인증이 없어 Principal이 없습니다. 그래서 **세션 ID 헤더를 함께 넣지 않으면 메시지가 아무에게도 가지 않습니다.** 브로커에 `/queue` prefix도 등록돼 있지 않았습니다.

### 문제 ⑥ (테스트로 발견) 마지막 위치가 아닌 "예전 위치"가 저장된다

- **현상**: 같은 클라이언트가 이동 메시지를 `(10,20)` → `(30,40)` 순서로 보냈는데, 저장소에 **`(10,20)`이 최종값으로 남았습니다.** 통합 테스트에서 7번 실행해 5번 실패했습니다.
- **원인**: **TCP는 순서를 보장하지만, Spring은 그 순서를 보장하지 않았습니다.** STOMP로 들어온 메시지는 `clientInboundChannel`의 **스레드 풀**에서 처리됩니다. 같은 세션의 메시지라도 **서로 다른 스레드에서 병렬로 처리될 수 있어**, 뒤에 온 메시지가 먼저 끝날 수 있습니다.
- **게임에서의 증상**: 다른 플레이어 캐릭터가 **순간적으로 뒤로 튀었다가 돌아오는** 현상이나, 멈춘 뒤 **엉뚱한 위치에 서 있는** 현상으로 나타납니다.

```
테스트 실패 시 저장소 상태 (디버그 출력)
DEBUG timeout store=[PlayerState(playerId=alice, x=10.0, y=20.0, ...)]   ← 30,40 이어야 함
```

> 이 문제는 처음에 설계한 테스트가 우연히 잡아냈습니다. "TCP니까 순서는 보장된다"는 가정이 **애플리케이션 계층에서는 깨질 수 있다**는 것을 확인한 경험입니다.

---

## 4. 해결

### 4-1. 저장소 추상화: `PlayerRepository`

Redis 접근을 Service에서 분리했습니다. 테스트에서는 Redis 없이 메모리 구현으로 바꿔 끼울 수 있습니다.

```java
public interface PlayerRepository {
    boolean saveIfAbsent(PlayerState state);                                  // HSETNX
    List<PlayerState> findAll(String roomId);                                 // HVALS
    int updateAllIfPresent(String roomId, Collection<PlayerState> states);    // Lua Script
    boolean delete(String roomId, String playerId);                           // HDEL
    void deleteAllRooms();                                                    // SCAN + DEL
}
```

### 4-2. 닉네임 중복 → `HSETNX`로 원자적 선점 (문제 ②)

```java
// RedisPlayerRepository
public boolean saveIfAbsent(PlayerState state) {
    return Boolean.TRUE.equals(hash().putIfAbsent(key(state.getRoomId()), state.getPlayerId(), state));
}

// GameService.joinPlayer
if (!playerRepository.saveIfAbsent(playerState)) {
    sendError(sessionId, "NICKNAME_TAKEN", "Nickname already in use: " + nickname);
    return;
}
```

"확인 후 저장"을 두 번의 명령으로 나누지 않고 **`HSETNX` 한 번**으로 처리합니다. 동시에 두 명이 같은 닉네임으로 들어와도 **한 명만 성공**합니다.

### 4-3. 클라이언트 값 대신 세션 기준으로 식별 (문제 ③)

입장에 성공하면 **WebSocket 세션 속성**에 `nickname`, `roomId`, `role`을 저장합니다. 이후 이동과 퇴장은 **클라이언트가 보낸 값이 아니라 세션 값**으로 처리합니다.

```java
// 입장 성공 시
sessionAttributes.put(ATTR_NICKNAME, nickname);
sessionAttributes.put(ATTR_ROOM_ID, roomId);
sessionAttributes.put(ATTR_ROLE, role.get());

// 이동 시: MoveRequest 에는 x, y, direction, sentAt 만 있다
String nickname = (String) sessionAttributes.get(ATTR_NICKNAME);
String roomId   = (String) sessionAttributes.get(ATTR_ROOM_ID);
if (nickname == null || roomId == null) return; // 입장하지 않은 세션은 무시
```

- `room:null` 키가 더 이상 생기지 않습니다.
- 입장·이동·퇴장이 **항상 같은 키**를 봅니다.
- 다른 사람의 캐릭터를 조작하는 요청이 **구조적으로 불가능**해졌습니다.
- 좌표는 맵 크기(800×600)로 clamp하고, 닉네임 길이(1~16자)와 역할 값도 서버에서 검증합니다.

### 4-4. 이동은 즉시 브로드캐스트, Redis 저장은 100ms마다 묶어서 (문제 ④)

```java
// 이동: Redis 를 기다리지 않는다
public void movePlayer(MoveRequest move, Map<String, Object> sessionAttributes) {
    ...
    broadcast(roomId, ServerMessage.of(ServerMessage.Type.MOVE, playerState, move.getSentAt()));
    pendingMoves.computeIfAbsent(roomId, id -> new ConcurrentHashMap<>()).put(nickname, playerState);
}

// 100ms 마다 방 단위로 최신 위치만 저장
@Scheduled(fixedDelayString = "${snowpeak.sync.flush-interval-ms:100}")
public void flushPendingMoves() {
    pendingMoves.forEach((roomId, moves) -> {
        Map<String, PlayerState> batch = new HashMap<>(moves);
        playerRepository.updateAllIfPresent(roomId, batch.values());
        batch.forEach(moves::remove); // remove(key, value): 그 사이 더 새 값이 왔으면 지우지 않음
    });
}
```

| 항목 | Before | After |
| --- | --- | --- |
| 이동 1회당 Redis 호출 | 1회 (동기, 브로드캐스트 전) | 0회 (메모리에 최신값만 덮어씀) |
| Redis 호출 수 (10명, 각 20Hz) | 초당 200회 | **방당 초당 최대 10회** (100ms마다 Lua 1회) |
| 위치 전달 경로의 Redis 대기 | 있음 | **없음** |
| 새 입장자 SYNC 정확도 | Redis 값 | Redis 값 + 아직 저장 안 된 최신 위치를 덮어써서 전송 |

- 같은 플레이어가 100ms 안에 여러 번 움직이면 **마지막 위치 하나만** 저장됩니다. 위치는 "최신 값만 의미 있는" 데이터라서 손실이 없습니다.
- `batch.forEach(moves::remove)`는 `Map.remove(key, value)`입니다. 저장하는 사이에 더 새로운 위치가 들어왔다면 **지우지 않고 다음 주기에 저장**합니다.

**트레이드오프**: 서버가 죽으면 마지막 100ms 안의 위치는 Redis에 남지 않습니다. 하지만 서버가 죽으면 세션도 모두 끊기므로, 그 위치는 어차피 쓸모가 없습니다(4-6 참고).

### 4-5. 쓰기 지연이 만든 새 경쟁 조건 → Lua Script로 해결

쓰기 지연을 넣자 **새로운 유령 플레이어 시나리오**가 생겼습니다.

```
t0  flush 가 pending 에서 alice 위치를 꺼냄
t1  alice 연결 끊김 → HDEL room:1 alice
t2  flush 가 HSET room:1 alice ...   ← 나간 플레이어가 되살아남
```

해결 방법은 **"아직 방에 있을 때만 저장"을 Redis 안에서 원자적으로** 실행하는 것입니다.

```lua
-- UPDATE_IF_PRESENT : KEYS[1] = room:{id}, ARGV = field1, json1, field2, json2, ...
local updated = 0
for i = 1, #ARGV, 2 do
  if redis.call('HEXISTS', KEYS[1], ARGV[i]) == 1 then
    redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
    updated = updated + 1
  end
end
return updated
```

- Redis는 Lua Script를 **단일 명령처럼 원자적으로** 실행합니다. 확인과 저장 사이에 `HDEL`이 끼어들 수 없습니다.
- 방 하나의 모든 변경을 **스크립트 1회(네트워크 왕복 1회)** 로 처리해, 배치 저장도 함께 해결합니다.
- **직렬화 함정**: Lua 인자는 문자열로 넘어갑니다. 그래서 `RedisTemplate`의 해시 값 직렬화기(`GenericJackson2JsonRedisSerializer`)로 **미리 JSON을 만들어 넘겼습니다.** 이렇게 해야 일반 `HSET`으로 저장한 값과 **같은 형식**으로 저장되고, `HVALS`로 다시 읽을 수 있습니다. 실제 Redis 테스트로 이를 검증했습니다.

```java
RedisSerializer<Object> valueSerializer = (RedisSerializer<Object>) redisTemplate.getHashValueSerializer();
args[i++] = state.getPlayerId();
args[i++] = new String(valueSerializer.serialize(state), StandardCharsets.UTF_8);

redisTemplate.execute(UPDATE_IF_PRESENT, RedisSerializer.string(),
        new GenericToStringSerializer<>(Long.class), List.of(key(roomId)), args);
```

퇴장할 때는 메모리 쪽 대기 중인 위치도 함께 지웁니다. 퇴장 판정은 `HEXISTS` + `HDEL` 두 번이 아니라 **`HDEL`의 반환값(삭제 개수) 하나**로 처리합니다.

```java
Map<String, PlayerState> pending = pendingMoves.get(roomId);
if (pending != null) pending.remove(nickname);

if (playerRepository.delete(roomId, nickname)) {   // HDEL 결과가 1일 때만 퇴장 알림
    broadcast(roomId, ServerMessage.of(ServerMessage.Type.PLAYER_LEFT, Map.of("playerId", nickname)));
}
```

### 4-6. 서버 시작 시 유령 데이터 정리 (문제 ①)

```java
@EventListener(ApplicationReadyEvent.class)
public void clearStalePlayers() {
    playerRepository.deleteAllRooms(); // SCAN room:* → DEL
}
```

- `KEYS room:*`는 Redis를 블로킹하므로 **`SCAN`으로 나눠서 조회**했습니다.
- `snowpeak.redis.clear-on-startup` 속성으로 끌 수 있게 했습니다.
- **전제**: 이 정리는 **서버가 1대일 때만 안전합니다.** 서버가 여러 대라면 다른 서버의 정상 플레이어까지 지우게 됩니다. 그때는 서버별 키 분리나 플레이어별 TTL + Heartbeat 갱신 방식으로 바꿔야 합니다(6장).

### 4-7. 1:1 메시지가 실제로 도착하도록 (문제 ⑤)

```java
// Principal 이 없는 세션에 1:1 전송 - 세션 ID 헤더가 있어야 /user/queue/** 로 전달된다
private void sendToSession(String sessionId, String destination, ServerMessage message) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setSessionId(sessionId);
    headers.setLeaveMutable(true);
    messagingTemplate.convertAndSendToUser(sessionId, destination, message, headers.getMessageHeaders());
}
```

- 브로커에 `/queue` prefix를 추가했습니다: `config.enableSimpleBroker("/topic", "/queue")`
- 클라이언트는 `/user/queue/sync`, `/user/queue/errors`, `/user/queue/pong`을 구독합니다.
- 기존에는 플레이어 수만큼 메시지를 보냈지만, 이제는 **`ROOM_STATE` 메시지 1개에 방 전체 목록**을 담아 보냅니다.
- 이전 커밋(`957ab29`)의 `/topic/private/{nickname}` 방식은 **아무나 그 토픽을 구독하면 남의 메시지를 받을 수 있는** 구조여서 사용하지 않았습니다.

### 4-8. 메시지 처리 순서 보장 (문제 ⑥)

```java
// WebSocketConfig
config.setPreservePublishOrder(true);    // 세션별 발신 순서 보장
registry.setPreserveReceiveOrder(true);  // 세션별 수신 순서 보장 (Spring 6.1+)
```

- `setPreserveReceiveOrder(true)`: 같은 세션에서 들어온 메시지를 **도착한 순서대로 하나씩** 처리합니다. 다른 세션끼리는 계속 병렬로 처리되므로 전체 처리량은 유지됩니다.
- `setPreservePublishOrder(true)`: 한 클라이언트에게 나가는 브로드캐스트도 **보낸 순서대로** 전달합니다.
- 테스트는 같은 세션에서 이동 30개를 연속으로 보낸 뒤 **마지막 좌표 `(30,40)`이 저장되는지** 확인하도록 강화했습니다. 전체 테스트를 **5회 반복 실행해 모두 통과**했습니다.

### 4-9. 역할(Role) 변환 예외로 입장이 실패하던 문제

프론트엔드는 역할을 `"server"`, `"cook"`으로 보냅니다. 그런데 서버 enum에는 `COOK`이 없었고, `Role.valueOf()`가 `IllegalArgumentException`을 던져 **요리사 역할은 입장 자체가 불가능했습니다.**

```java
public static Optional<Role> from(String value) {
    if (value == null) return Optional.empty();
    try {
        return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
        return Optional.empty();
    }
}
```

`COOK`을 추가하고, 모르는 값이 오면 예외 대신 `INVALID_ROLE` 에러 메시지를 해당 사용자에게만 보냅니다.

---

## 5. 검증

### 5-1. 테스트 구성

| 테스트 | 환경 | 개수 | 검증 내용 |
| --- | --- | --- | --- |
| `GameSyncIntegrationTest` | 실제 서버(랜덤 포트) + Java STOMP/SockJS 클라이언트 + 메모리 저장소 | 8 | 아래 표 |
| `RedisPlayerRepositoryTest` | **실제 Redis** (`localhost:6379`, 없으면 자동 skip) | 3 | HSETNX 중복 거절, Lua 조건부 갱신 + 역직렬화 호환, HDEL 반환값 |

| 통합 테스트 시나리오 | 확인한 문제 |
| --- | --- |
| 늦은 입장자가 `ROOM_STATE`로 기존 플레이어 전체를 받는다 | ⑤ |
| 이동이 서버 세션 기준 ID, `roomId=1`, 원본 `sentAt`과 함께 브로드캐스트된다 | ③ |
| 연속 이동 30개 후 마지막 위치가 저장되고 SYNC에 반영된다 | ④ ⑥ |
| 연결이 끊기면 `PLAYER_LEFT`가 브로드캐스트되고 저장소에서 삭제된다 | ① |
| 같은 닉네임 입장은 `NICKNAME_TAKEN`으로 거절되고 기존 데이터는 그대로다 | ② |
| 모르는 역할은 예외 없이 `INVALID_ROLE`로 거절된다 | 4-9 |
| ping에 원본 타임스탬프로 pong을 돌려준다 (RTT 측정용) | — |
| 퇴장 직후 늦게 온 이동이 flush되어도 플레이어가 되살아나지 않는다 | 4-5 |

### 5-2. 결과

```
RedisPlayerRepositoryTest   tests=3  skipped=0  failures=0
GameSyncIntegrationTest     tests=8  skipped=0  failures=0
→ 전체 11개 통과, 5회 반복 실행 모두 성공
```

---

## 6. 아직 남은 과제

| 과제 | 내용 |
| --- | --- |
| **수평 확장** | Simple Broker는 서버 메모리 안에서만 메시지를 전달합니다. 서버를 2대 이상 띄우면 **Redis를 공유해도 서로의 브로드캐스트가 전달되지 않습니다.** 외부 브로커(RabbitMQ STOMP Relay)나 Redis Pub/Sub로 서버 간 메시지 중계가 필요합니다. README에 적힌 Pub/Sub는 아직 구현 전입니다. |
| 다중 서버에서의 유령 정리 | 시작 시 전체 삭제 대신 **플레이어별 TTL + Heartbeat 갱신** 또는 서버 ID별 키 분리 |
| 주문/서빙 동시성 | README에 적힌 "한 음식을 두 명이 동시에 집는 문제"는 이번에 만든 Lua Script 패턴을 그대로 확장해 적용할 예정 |
| 프론트엔드 반영 | 서버 메시지 형식(`{type, payload, serverTime, sentAt}`)과 `/user/queue/*` 구독을 프론트엔드에 반영해야 합니다. 이번 작업 범위는 백엔드까지입니다. |

---

## 7. 회고

1. **"원자성"은 명령 하나 단위로 생각해야 한다.** `HEXISTS` → `HSET`처럼 두 번 나눠 호출하는 순간 사이에 다른 요청이 끼어듭니다. `HSETNX`, `HDEL` 반환값, Lua Script처럼 **한 번에 끝나는 연산**으로 바꾸는 것이 핵심이었습니다.
2. **성능 최적화는 새로운 경쟁 조건을 만든다.** 쓰기 지연으로 Redis 호출을 줄이자, 곧바로 "퇴장한 플레이어 부활" 문제가 생겼습니다. 최적화를 넣을 때는 **기존 불변식(퇴장한 플레이어는 저장소에 없다)이 깨지지 않는지** 함께 검증해야 합니다.
3. **"TCP니까 순서가 보장된다"는 네트워크 계층까지의 이야기다.** 애플리케이션이 스레드 풀로 병렬 처리하면 순서는 다시 깨집니다. 이 문제는 테스트가 먼저 알려줬고, 재현되는 테스트를 만든 뒤 고쳤습니다.
4. **클라이언트가 보낸 식별자는 믿지 않는다.** 누가, 어느 방에서 보냈는지는 서버가 이미 알고 있는 세션 정보로 판단해야 버그와 보안 문제를 함께 막을 수 있습니다.
5. **저장소를 인터페이스로 분리하니 테스트가 쉬워졌다.** 통합 테스트는 Redis 없이 빠르게 돌리고, Redis 고유 동작(Lua, 직렬화)만 실제 Redis로 따로 검증하는 구조를 만들 수 있었습니다.

---

## 부록: 변경 파일

| 파일 | 변경 |
| --- | --- |
| `repository/PlayerRepository.java` | 신규 — 저장소 인터페이스 |
| `repository/RedisPlayerRepository.java` | 신규 — HSETNX, Lua `UPDATE_IF_PRESENT`, HDEL, SCAN 정리 |
| `repository/StalePlayerCleaner.java` | 신규 — 서버 시작 시 유령 데이터 정리 |
| `service/GameService.java` | 세션 기반 식별, 입력 검증, 쓰기 지연 flush, 1:1 전송 수정 |
| `controller/GameController.java` | `/join`, `/move`, `/ping` |
| `global/config/WebSocketConfig.java` | `/queue` 브로커, 수신/발신 순서 보장 |
| `dto/ServerMessage.java`, `MoveRequest.java`, `PingRequest.java` | 신규 — 서버↔클라이언트 메시지 계약 |
| `dto/PlayerState.java` | `COOK` 역할, 안전한 `Role.from()` |
| `listener/WebSocketEventListener.java` | 세션 속성 기반 퇴장 처리 |
| `src/test/...` | 통합 테스트 8개, Redis 테스트 3개 |
