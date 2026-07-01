package com.example.scheduler.tasks;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;

/**
 * Demonstrates one-time scheduling via TaskScheduler.
 *
 * @Scheduled has no built-in one-time mode.
 * Inject TaskScheduler and call schedule(Runnable, Instant) instead.
 */
@Component
public class OneTimeTask {

    private final TaskScheduler taskScheduler;

    public OneTimeTask(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    /**
     * Schedules two one-time tasks after the application context is fully up:
     *  - warm-up cache 5s after startup
     *  - send startup notification 10s after startup
     */
    @PostConstruct
    public void scheduleOneTimeTasks() {
        Instant now = Instant.now();

        taskScheduler.schedule(this::warmUpCache, now.plusSeconds(5));
        taskScheduler.schedule(this::sendStartupNotification, now.plusSeconds(10));

        log("one-time tasks registered — warm-up in 5s, notification in 10s");
    }

    private void warmUpCache() {
        log("warming up cache...");
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        log("cache warm-up complete");
    }

    private void sendStartupNotification() {
        log("sending startup notification to ops team");
    }

    private static void log(String msg) {
        System.out.printf("[%s] [%-20s] [one-time      ] %s%n",
            Instant.now(), Thread.currentThread().getName(), msg);
    }
}
