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
 * Simulates a slow report-generation job (3s execution time).
 *
 * Demonstrates:
 *  - @SchedulerLock preventing duplicate execution across pods
 *  - lockAtMostFor = "PT30S": if this pod crashes, lock expires in 30s (failsafe)
 *  - lockAtLeastFor = "PT5S": lock stays held 5s after completion
 *    (absorbs clock skew — prevents another pod with a slightly-ahead clock
 *     from re-running the same job within the same trigger window)
 *  - LockAssert.assertLocked(): verifies ShedLock wiring at runtime
 *
 * Inspect the lock table while this runs:
 *   http://localhost:8080/h2-console → SELECT * FROM shedlock;
 *
 * You will see:
 *   name      = "generateMonthlyReport"
 *   locked_by = this pod's ID
 *   lock_until = ~5s from job completion (lockAtLeastFor window)
 */
@Component
public class ReportJob {

    private static final Logger log = LoggerFactory.getLogger(ReportJob.class);

    private final String podId;

    public ReportJob(PodIdentity podIdentity) {
        this.podId = podIdentity.getId();
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 2_000)
    @SchedulerLock(
        name           = "generateMonthlyReport",
        lockAtMostFor  = "PT30S",   // failsafe: crash recovery in 30s
        lockAtLeastFor = "PT5S"     // minimum hold: absorbs up to 5s clock skew
    )
    public void generateReport() {
        // Throws IllegalStateException if ShedLock is misconfigured
        // (e.g. @EnableSchedulerLock missing, or method called directly in tests).
        LockAssert.assertLocked();

        log.info("[{}] *** LOCK ACQUIRED — generateMonthlyReport — {}", podId, Instant.now());

        simulateWork("Aggregating sales data", 1_000);
        simulateWork("Rendering PDF",          1_000);
        simulateWork("Uploading to S3",        1_000);

        log.info("[{}] *** Report complete. Lock held for lockAtLeastFor(5s) before other pods can acquire.", podId);
        // After return: ShedLock sets lock_until = NOW() + 5s (lockAtLeastFor).
    }

    private void simulateWork(String step, long ms) {
        log.info("[{}]   → {}", podId, step);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
