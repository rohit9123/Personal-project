package com.example.scheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Enables scheduling and wires a 4-thread pool so tasks don't block each other.
 *
 * Without this, Spring uses a single-threaded executor — one slow task
 * delays every other scheduled task.
 *
 * ThreadPoolTaskScheduler also implements TaskScheduler, so it satisfies the
 * injection point in OneTimeTask without a separate bean.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setScheduler(taskScheduler());
    }

    @Bean(destroyMethod = "shutdown")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("demo-sched-");
        scheduler.setErrorHandler(t ->
            System.err.println("[SCHEDULER ERROR] " + t.getMessage()));
        scheduler.initialize();
        return scheduler;
    }
}
