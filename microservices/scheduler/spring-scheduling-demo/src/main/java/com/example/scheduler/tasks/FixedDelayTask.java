package com.example.scheduler.tasks;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates scheduleWithFixedDelay behaviour.
 *
 * Next start = previous END + delay.
 * The gap between runs is always at least `delay`, regardless of task duration.
 * Safe for tasks that must not overlap (e.g., DB polling, inbox processing).
 */
@Component
public class FixedDelayTask {

    private final AtomicInteger count = new AtomicInteger(0);

    // 4s gap after each run completes — next run starts 4s after the previous one finishes
    @Scheduled(fixedDelay = 4, timeUnit = TimeUnit.SECONDS)
    public void inboxPoller() {
        int run = count.incrementAndGet();
        log("inbox-poller", "run #" + run + " START — polling inbox");

        // Simulate variable work (2s)
        sleep(2000);

        log("inbox-poller", "run #" + run + " END  — next run in 4s from now");
    }

    // Reads delay from application.properties
    @Scheduled(fixedDelayString = "${app.scheduler.cleanup-delay-ms}")
    public void dbCleanup() {
        log("db-cleanup", "deleting expired records");
        sleep(500); // fast cleanup
        log("db-cleanup", "done");
    }

    private static void log(String task, String msg) {
        System.out.printf("[%s] [%-20s] [%-14s] %s%n",
            Instant.now(), Thread.currentThread().getName(), task, msg);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
