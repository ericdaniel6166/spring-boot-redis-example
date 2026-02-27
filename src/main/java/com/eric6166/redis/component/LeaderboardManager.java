package com.eric6166.redis.component;

import com.eric6166.redis.dto.LeaderboardType;
import com.eric6166.redis.service.RankingService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class LeaderboardManager {

    private final RankingService rankingService;

    private final ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Bootstrap: Rehydrating all leaderboards and profiles...{}", LocalDateTime.now(ZoneOffset.UTC));

        // 1. Load Scores
        Arrays.stream(LeaderboardType.values()).forEach(type ->
                taskExecutor.submit(() -> rankingService.rehydrateFromDb(type, 1))
        );

        // 2. Load Profiles (Single call handles batching internally)
        taskExecutor.submit(rankingService::rehydrateAllProfiles);
    }

    @Scheduled(fixedDelay = 300_000) // Every 5 minutes
    @SchedulerLock(name = "LB_Sync_To_DB", lockAtMostFor = "4m", lockAtLeastFor = "1m")
    public void periodicSync() {
        log.info("Scheduled Task: Syncing all boards to Postgres...{}", LocalDateTime.now(ZoneOffset.UTC));

        // Parallel sync: One board's latency won't affect the others
        for (LeaderboardType type : LeaderboardType.values()) {
            taskExecutor.submit(() -> {
                try {
                    rankingService.syncToDb(type, 1);
                } catch (Exception e) {
                    log.error("Failed to sync {} leaderboard: {}", type, e.getMessage());
                }
            });
        }
    }

    @Scheduled(cron = "0 0 2 * * *") // 2 AM Daily
    @SchedulerLock(name = "LB_Nightly_Reconciliation", lockAtMostFor = "15m", lockAtLeastFor = "5m")
    public void nightlyReconciliation() {
        log.info("Nightly Task: Full rehydration from source of truth...{}", LocalDateTime.now(ZoneOffset.UTC));

        for (LeaderboardType type : LeaderboardType.values()) {
            taskExecutor.submit(() -> rankingService.rehydrateFromDb(type, 1));
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutdown: Closing LeaderboardManager task executor...{}", LocalDateTime.now(ZoneOffset.UTC));
        taskExecutor.shutdown();
        try {
            // Wait up to 30 seconds for existing tasks to terminate
            if (!taskExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                taskExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            taskExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}