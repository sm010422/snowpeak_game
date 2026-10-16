# 트러블슈팅: 로그인 없는 STOMP 세션에 1:1 메시지가 도착하지 않는 문제

> **프로젝트**: Snowpeak Game
> **스택**: Spring WebSocket STOMP, Simple Broker, SockJS
> **한 줄 요약**: 늦게 들어온 플레이어에게 "기존 플레이어 목록"을 보냈지만 **아무 데도 도착하지 않았습니다.** 인증(Principal)이 없는 환경에서 `convertAndSendToUser`가 동작하는 조건을 확인해 고쳤고, 토픽 하나로 브로드캐스트하던 개인 메시지의 보안 문제도 함께 정리했습니다.

---

## 1. 문제

새 플레이어가 들어오면, 그 사람에게만 현재 방에 있는 플레이어 목록을 보내야 합니다. 여러 번의 시도가 있었습니다.

| 시점 | 방법 | 결과 |
| --- | --- | --- |
| `9670dfa` | `convertAndSendToUser(sessionId, "/queue/players", ps)`를 플레이어 수만큼 반복 | **도착하지 않음** |
| `957ab29` | `convertAndSend("/topic/private/" + nickname, syncMessage)` | 도착은 하지만 **누구나 구독 가능** |
| 현재 | 세션 ID 헤더를 붙인 `convertAndSendToUser` + `/user/queue/sync` | **본인 세션에만 도착** (테스트로 검증) |

## 2. 원인 1: `convertAndSendToUser`의 "user"는 기본적으로 로그인 사용자다

`convertAndSendToUser(user, "/queue/x", payload)`는 내부에서 목적지를 `/user/{user}/queue/x`로 만듭니다. 그리고 `UserDestinationMessageHandler`가 이 **user 이름에 연결된 세션들**을 찾아 `/queue/x-user{sessionId}`로 바꿔 전달합니다.

- user와 세션의 연결 정보는 **`Principal`(인증된 사용자)** 기준으로 관리됩니다.
- 이 프로젝트는 로그인이 없어서 Principal이 `null`입니다. 그래서 `sessionId`를 user 이름 자리에 넣어도 **연결된 세션을 찾지 못해 메시지가 버려집니다.** 오류 로그도 남지 않아 원인을 찾기 어려웠습니다.
- 브로커에 `/queue` prefix도 등록돼 있지 않았습니다(`enableSimpleBroker("/topic")`만 있음).

## 3. 원인 2: `/topic/private/{nickname}`은 "비공개"가 아니다

`957ab29`에서는 1:1 전송을 포기하고 닉네임별 토픽으로 보냈습니다. 동작은 했지만 다음 문제가 있습니다.

- 토픽은 **구독만 하면 누구나 받을 수 있습니다.** 다른 사람의 닉네임을 알면 그 사람의 개인 메시지를 받을 수 있습니다.
- 메시지를 보낸 뒤에 클라이언트가 구독하면 **메시지를 놓칩니다.** 순서 보장이 없습니다.
- 프론트엔드는 이 토픽을 구독하고 있지 않아, 실제로는 **누구도 받지 못했습니다.**

## 4. 해결

### 4-1. 세션 ID를 헤더에 넣어 보낸다

Spring은 메시지 헤더에 **세션 ID가 있고, user 이름이 그 세션 ID와 같으면** Principal 없이도 해당 세션으로 전달합니다.

```java
// GameService
private void sendToSession(String sessionId, String destination, ServerMessage message) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setSessionId(sessionId);
    headers.setLeaveMutable(true);
    messagingTemplate.convertAndSendToUser(sessionId, destination, message, headers.getMessageHeaders());
}
```

```java
// WebSocketConfig
config.enableSimpleBroker("/topic", "/queue");
```

클라이언트는 자기 세션 ID를 몰라도 됩니다. **`/user/queue/sync`** 만 구독하면 Spring이 세션별 목적지로 연결해 줍니다.

### 4-2. 한 곳에서 여러 용도로 재사용

| 목적지 | 용도 |
| --- | --- |
| `/user/queue/sync` | 입장 직후 방 전체 상태(`ROOM_STATE`) |
| `/user/queue/errors` | `NICKNAME_TAKEN`, `INVALID_ROLE`, `INVALID_NICKNAME` |
| `/user/queue/pong` | 지연(RTT) 측정용 응답 |

### 4-3. N개 메시지 → 1개 메시지

기존에는 기존 플레이어 수만큼 메시지를 보냈습니다. 지금은 **`ROOM_STATE` 하나에 목록 전체**를 담습니다. 클라이언트는 메시지 하나로 화면을 한 번에 그릴 수 있고, 일부만 도착한 중간 상태가 생기지 않습니다.

### 4-4. 구독 → 입장 순서를 지킨다

1:1 메시지도 **구독하기 전에 보내면 사라집니다.** 그래서 클라이언트는 연결 직후 모든 구독을 먼저 끝내고 `/app/join`을 보내야 합니다. 테스트 클라이언트도 이 순서를 따르도록 작성했습니다.

## 5. 검증 (`GameSyncIntegrationTest`)

| 테스트 | 확인 내용 |
| --- | --- |
| `lateJoinerReceivesRoomStateWithExistingPlayers` | bob이 `/user/queue/sync`로 `ROOM_STATE`를 받고, 목록에 alice와 bob이 모두 있음 |
| `duplicateNicknameIsRejectedWithoutOverwriting` | 두 번째 alice만 `/user/queue/errors`로 `NICKNAME_TAKEN`을 받음 |
| `unknownRoleIsRejectedInsteadOfThrowing` | `/user/queue/errors`로 `INVALID_ROLE`을 받음 |
| `pingIsAnsweredWithOriginalTimestamp` | `/user/queue/pong`으로 보낸 `sentAt` 그대로 돌아옴 |

## 6. 배운 점

- **메시지가 "조용히" 사라지는 경로가 가장 위험합니다.** 예외도 로그도 없어서, 테스트로 실제 도착을 확인하기 전까지 동작하는 줄 알았습니다.
- **"비공개"는 목적지 이름이 아니라 라우팅 방식으로 보장해야 합니다.** `/topic/private/...`처럼 이름만 private인 토픽은 보안 장치가 아닙니다.
- 나중에 로그인을 붙이면 Principal 기반 `convertAndSendToUser(username, ...)`로 자연스럽게 바꿀 수 있습니다. 클라이언트 구독 경로(`/user/queue/...`)는 바뀌지 않습니다.
