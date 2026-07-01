package com.example.shedlock.jobs;

import com.example.shedlock.config.PodIdentity;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Simulates a fast DB cleanup job (expired session purge, <100ms actual work).
 *
 * Demonstrates:
 *  - A second independent lock row in the shedlock table ("cleanupExpiredSessions").
 *  - lockAtMostFor < fixedDelay: lock must expire before the next trigger fires.
 *    Here: lockAtMostFor=PT25S < fixedDelay=30s — no self-blocking.
 *  - lockAtLeastFor = "PT10S": job body is instant but we hold the lock 10s
 *    to prevent a second pod from re-running within the same window.
 *
 * Rule of thumb:
 *   lockAtMostFor  > max job duration       (pod crash recovery)
 *   lockAtLeastFor ≥ 2 × max clock skew     (typically 5s–30s)
 *   lockAtMostFor  < fixedDelay             (no self-blocking)
 */
@Component
public class CleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    private final String podId;

    public CleanupJob(PodIdentity podIdentity) {
        this.podId = podIdentity.getId();
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    @SchedulerLock(
        name           = "cleanupExpiredSessions",
        lockAtMostFor  = "PT25S",    // < fixedDelay(30s) — clears before next trigger
        lockAtLeastFor = "PT10S"     // hold 10s even though job completes instantly
    )
    public void cleanupExpiredSessions() {
        LockAssert.assertLocked();

        log.info("[{}] *** LOCK ACQUIRED — cleanupExpiredSessions — {}", podId, Instant.now());

        int deleted = purgeExpiredSessions();

        log.info("[{}] *** Cleanup done — deleted {} expired sessions. Lock held for lockAtLeastFor(10s).",
            podId, deleted);
    }

    private int purgeExpiredSessions() {
        // Real impl: jdbcTemplate.update("DELETE FROM sessions WHERE expires_at < NOW()");
        int count = (int) (Math.random() * 50);
        log.info("[{}]   → Purged {} expired sessions from DB", podId, count);
        return count;
    }
}
