package com.example.scheduler.tasks;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Demonstrates cron-based scheduling.
 *
 * Spring cron format (6 fields):
 *   second  minute  hour  day-of-month  month  day-of-week
 *
 * Practical examples — uncomment one at a time to test.
 * All tasks also show the "from config" pattern via ${...}.
 */
@Component
public class CronTask {

    // --- Every 10 seconds (for demo — so you can see it fire quickly) ---
    @Scheduled(cron = "0/10 * * * * *")
    public void everyTenSeconds() {
        log("cron-10s", "fires every 10 seconds");
    }

    // --- Every minute at second 0 ---
    // @Scheduled(cron = "0 * * * * *")
    // public void everyMinute() { log("cron-1m", "fires every minute"); }

    // --- Every 5 minutes ---
    // @Scheduled(cron = "0 */5 * * * *")
    // public void everyFiveMinutes() { log("cron-5m", "fires every 5 minutes"); }

    // --- Every day at 09:00 (weekdays only) ---
    // @Scheduled(cron = "0 0 9 * * MON-FRI")
    // public void morningReport() { log("morning-report", "generating daily report"); }

    // --- Last day of every month at 00:00 ---
    // @Scheduled(cron = "0 0 0 L * *")
    // public void endOfMonthJob() { log("eom-job", "end-of-month invoice generation"); }

    // --- 1st January at midnight ---
    // @Scheduled(cron = "0 0 0 1 1 *")
    // public void yearlyReset() { log("yearly", "annual data reset"); }

    // --- From application.properties ---
    @Scheduled(cron = "${app.scheduler.report-cron}")
    public void reportFromConfig() {
        log("report-cron", "scheduled report — cron from config");
    }

    // --- Cron with timezone ---
    @Scheduled(cron = "0/30 * * * * *", zone = "UTC")
    public void everyThirtySecondsUtc() {
        log("cron-30s-utc", "fires every 30s in UTC timezone");
    }

    private static void log(String task, String msg) {
        System.out.printf("[%s] [%-20s] [%-14s] %s%n",
            Instant.now(), Thread.currentThread().getName(), task, msg);
    }
}
