package com.example.scheduler.tasks;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates scheduleAtFixedRate behaviour.
 *
 * Next start = previous START + rate.
 * If a run takes longer than the rate, the next run fires immediately after.
 */
@Component
public class FixedRateTask {

    private final AtomicInteger count = new AtomicInteger(0);

    // Fires every 3 seconds measured from the previous START
    @Scheduled(fixedRate = 3, timeUnit = TimeUnit.SECONDS)
    public void heartbeat() {
        int run = count.incrementAndGet();
        log("heartbeat", "run #" + run + " START");

        // Simulate 1s of work — well within the 3s rate, so no pileup
        sleep(1000);

        log("heartbeat", "run #" + run + " END");
    }

    // Reads rate from application.properties — shows externalised config
    @Scheduled(fixedRateString = "${app.scheduler.metrics-rate-ms}")
    public void metricsPoller() {
        log("metrics-poller", "collecting metrics snapshot");
    }

    private static void log(String task, String msg) {
        System.out.printf("[%s] [%-20s] [%-14s] %s%n",
            Instant.now(), Thread.currentThread().getName(), task, msg);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
