# 테스트 전략: 실시간 동기화 코드를 어떻게 검증했나

> **결과**: 통합 테스트 8개 + 실제 Redis 테스트 3개, 전체 11개 통과. 5회 반복 실행 모두 성공.
> **실행**: `cd backend && ./gradlew test` (Redis가 `localhost:6379`에 없으면 Redis 테스트 3개는 자동 skip)

---

## 1. 왜 테스트가 필요했나

이 프로젝트에는 처음에 테스트가 하나도 없었습니다(`src/test` 디렉터리 없음). 그동안의 검증은 **브라우저 탭 두 개를 띄워 눈으로 확인**하는 방식이었고, 그 결과 다음 문제들이 오래 남아 있었습니다.

| 눈으로는 못 찾은 문제 | 왜 안 보였나 |
| --- | --- |
| 늦은 입장자에게 1:1 메시지가 도착하지 않음 | 예외도 로그도 없이 조용히 버려짐 |
| 같은 세션의 이동 순서 역전 | 혼자 로컬에서 천천히 움직이면 거의 발생하지 않음 |
| 퇴장 직후 flush로 플레이어 부활 | 수 ms 사이의 타이밍에서만 발생 |
| `cook` 역할로 입장 불가 | 테스트할 때 늘 `server`로만 입장 |

## 2. 테스트 구조

```
src/test/java/com/snowpeak/game/
├── repository/
│   ├── InMemoryPlayerRepository.java      # 테스트용 저장소 (Redis 대체)
│   └── RedisPlayerRepositoryTest.java     # 실제 Redis로 Lua·직렬화 검증
└── sync/
    └── GameSyncIntegrationTest.java       # 실제 서버 + STOMP 클라이언트 E2E
```

### 2-1. 통합 테스트: 실제 서버에 실제 STOMP 클라이언트로 접속

```java
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "snowpeak.sync.flush-interval-ms=50")
class GameSyncIntegrationTest {

    @TestConfiguration
    static class InMemoryStoreConfig {
        @Bean @Primary
        PlayerRepository inMemoryPlayerRepository() { return new InMemoryPlayerRepository(); }
    }
    ...
    stompClient = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
```

- **실제 Tomcat 서버를 랜덤 포트로** 띄우고, 브라우저와 같은 **SockJS + STOMP** 경로로 접속합니다. 컨트롤러를 직접 호출하는 단위 테스트로는 라우팅·직렬화·순서 문제를 잡을 수 없기 때문입니다.
- 클라이언트마다 `/topic/room.1`, `/user/queue/sync`, `/user/queue/errors`, `/user/queue/pong`을 구독하고, 받은 메시지를 `BlockingQueue`에 쌓습니다.
- `awaitType(queue, "MOVE")`처럼 **원하는 타입의 메시지가 올 때까지 최대 3초 기다립니다.** 고정된 `sleep`에 의존하지 않아 느린 환경에서도 안정적입니다.
- 저장소는 `PlayerRepository` 인터페이스 덕분에 **메모리 구현으로 교체**했습니다. 그래서 Redis 없이도 빠르게 돌아갑니다.

### 2-2. Redis 테스트: Redis만 할 수 있는 것은 Redis로

메모리 구현으로는 **Lua Script와 직렬화 호환성**을 검증할 수 없습니다. 그래서 `RedisPlayerRepository`는 실제 Redis에 붙여 따로 테스트합니다.

```java
assumeTrue(redisUp, "Redis is not running on localhost:6379");
```

- Redis가 없으면 실패 대신 **skip** 합니다. CI에 Redis가 없어도 빌드가 깨지지 않습니다.
- 매 실행마다 `test-{nanoTime}` 키를 써서 **실제 데이터와 섞이지 않게** 했습니다.

| 테스트 | 검증 |
| --- | --- |
| `saveIfAbsentRejectsDuplicateNickname` | `HSETNX`로 두 번째 저장이 거절되고 첫 값이 유지됨 |
| `updateAllIfPresentOnlyTouchesExistingPlayersAndStaysReadable` | Lua가 존재하는 필드만 갱신(1개 갱신, 1개 무시). **Lua로 저장한 JSON을 `HVALS`로 다시 객체로 읽을 수 있음** |
| `deleteReportsWhetherPlayerExisted` | `HDEL` 반환값으로 퇴장 여부를 판단할 수 있음 |

### 2-3. 타이밍 버그는 "상황을 직접 만들어서" 테스트

"퇴장 직후 늦게 온 이동"처럼 실제로는 수 ms 사이에만 생기는 상황은 기다려서 재현하지 않았습니다. **서비스 메서드를 그 순서대로 직접 호출**해 상황을 만들었습니다.

```java
gameService.removePlayer(attrs);                       // 퇴장
gameService.movePlayer(new MoveRequest(5, 5, ...), attrs);  // 늦게 도착한 이동
gameService.flushPendingMoves();                       // flush

assertThat(playerRepository.findAll("1")).isEmpty();   // 되살아나면 안 됨
```

## 3. 시나리오 목록

| # | 테스트 | 막는 회귀 |
| --- | --- | --- |
| 1 | 늦은 입장자가 `ROOM_STATE`로 전체 목록을 받는다 | 1:1 메시지 미도착 |
| 2 | 이동이 세션 기준 ID·방·`sentAt`과 함께 브로드캐스트된다 | 클라이언트 ID 신뢰, `room:null` |
| 3 | 연속 이동 30개 후 마지막 위치가 저장되고 SYNC에 반영된다 | 순서 역전, 쓰기 지연 누락 |
| 4 | 연결 끊김 → `PLAYER_LEFT` + 저장소 삭제 | 유령 캐릭터 |
| 5 | 중복 닉네임 거절, 기존 데이터 유지 | 덮어쓰기 |
| 6 | 모르는 역할은 예외 대신 `INVALID_ROLE` | 입장 실패 |
| 7 | ping → 원본 `sentAt`으로 pong | RTT 측정 경로 |
| 8 | 퇴장 후 flush가 플레이어를 되살리지 않는다 | 쓰기 지연 경쟁 조건 |
| 9~11 | Redis: HSETNX, Lua 조건부 갱신 + 역직렬화, HDEL 반환값 | Redis 명령·직렬화 |

## 4. 테스트가 실제로 잡아낸 버그

3번 시나리오는 처음 작성했을 때 **7회 중 5회 실패**했습니다. 원인은 Spring STOMP의 inbound 스레드 풀이 같은 세션의 메시지를 병렬로 처리해 **이동 순서가 뒤바뀐 것**이었습니다. 자세한 분석은 [메시지 순서 역전 트러블슈팅](./troubleshooting-message-ordering.md)에 있습니다.

> 이 경험으로, **동시성 문제를 "가끔 실패하는 테스트"로 무시하지 않는 것**이 중요하다는 것을 배웠습니다. 간헐적 실패는 대개 실제 버그입니다.

## 5. 아직 없는 테스트

| 영역 | 계획 |
| --- | --- |
| 프론트엔드 보간 로직 | `MainScene`의 보간·전송 주기 로직을 순수 함수로 분리해 Vitest로 검증 |
| 부하 | 봇 N명이 20Hz로 움직일 때 `MOVE`의 `Date.now() - sentAt` p50/p95 측정 스크립트 |
| CI | GitHub Actions에 Redis 서비스 컨테이너를 붙여 Redis 테스트까지 자동 실행 |
