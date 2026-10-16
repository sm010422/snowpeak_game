# Snowpeak Game 문서

| 문서 | 내용 | 추천 용도 |
| --- | --- | --- |
| [포트폴리오 요약](./portfolio-summary.md) | 이력서용 한 줄 요약, 트러블슈팅 4건 STAR 정리, 아키텍처, 배운 점 | 포트폴리오 첫 페이지 |
| [이동 동기화 트러블슈팅](./troubleshooting-movement-sync.md) | TCP vs UDP, 전송 주기 제한, 보간, 입장/퇴장 동기화, 되돌린 리팩터링 | 대표 트러블슈팅 |
| [Redis 상태 관리 트러블슈팅](./troubleshooting-redis-player-state.md) | 유령 플레이어, HSETNX, 쓰기 지연, Lua Script, 세션 기반 식별 | Redis·동시성 역량 |
| [메시지 순서 역전 트러블슈팅](./troubleshooting-message-ordering.md) | TCP 순서 보장이 Spring 스레드 풀에서 깨진 문제 | 디버깅 과정 어필 |
| [STOMP 1:1 메시지 트러블슈팅](./troubleshooting-stomp-user-destination.md) | Principal 없는 세션에 개인 메시지 전달, 비공개 토픽 문제 | Spring WebSocket 이해도 |
| [메시지 계약 명세](./message-protocol.md) | 엔드포인트, 메시지 형식, 에러 코드, 지연 측정 방법 | 기술 문서 작성 역량 |
| [테스트 전략](./testing-strategy.md) | 통합 테스트·Redis 테스트 구조, 테스트가 잡은 버그 | 테스트 역량 |
| [면접 예상 Q&A](./interview-qna.md) | 질문 14개와 답변 요지 | 면접 준비용 (외부 공개 X) |

## 기준 시점

- 커밋 이력: `7ddcf48` ~ `957ab29` (2025-12-15 ~ 2026-01-07)
- 이후 백엔드 수정과 테스트 11개: `04e16bc` ~ `5d44e94`
- 프론트엔드(`frontend/`)는 이 저장소에서 버전 관리되지 않습니다. 새 메시지 계약 반영도 아직 남아 있습니다.

## 테스트 실행

```bash
cd backend
redis-server --daemonize yes   # 선택: 없으면 Redis 테스트 3개는 skip
./gradlew test
```
