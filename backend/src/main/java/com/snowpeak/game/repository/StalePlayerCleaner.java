package com.snowpeak.game.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 서버가 비정상 종료되면 SessionDisconnectEvent 가 오지 않아 Redis 에 플레이어가 남는다.
// Simple Broker 의 세션은 서버 메모리에만 있으므로, 서버가 새로 뜬 시점의 플레이어 데이터는 모두 유령이다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "snowpeak.redis.clear-on-startup", havingValue = "true", matchIfMissing = true)
public class StalePlayerCleaner {

    private final PlayerRepository playerRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void clearStalePlayers() {
        try {
            playerRepository.deleteAllRooms();
            log.info("Cleared stale player data on startup");
        } catch (Exception e) {
            log.warn("Could not clear stale player data: {}", e.getMessage());
        }
    }
}
