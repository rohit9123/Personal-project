package com.example.orderservice;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Subscribes to Resilience4j retry events so we can see each attempt logged.
 *
 * Retry instances are created lazily on first use, so we subscribe two ways:
 *   1. onEntryAdded — catches any retry registered after startup
 *   2. ApplicationRunner.run — catches retries already in the registry at startup
 *
 * Without this, retries happen silently inside the AOP proxy — the logs here
 * make the timing and attempt numbers visible during demos.
 */
@Component
public class RetryEventLogger implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(RetryEventLogger.class);

    private final RetryRegistry retryRegistry;

    public RetryEventLogger(RetryRegistry retryRegistry) {
        this.retryRegistry = retryRegistry;
        // Subscribe to retries added lazily after startup (the common case with @Retry)
        retryRegistry.getEventPublisher()
                .onEntryAdded(event -> subscribe(event.getAddedEntry()));
    }

    @Override
    public void run(ApplicationArguments args) {
        // Subscribe to any retries already present at startup time
        retryRegistry.getAllRetries().forEach(this::subscribe);
    }

    private void subscribe(Retry retry) {
        retry.getEventPublisher()
                .onRetry(e -> logger.info(
                        "[{}] attempt #{} FAILED — retrying due to: {}",
                        e.getName(),
                        e.getNumberOfRetryAttempts(),
                        e.getLastThrowable().getClass().getSimpleName()))
                .onSuccess(e -> logger.info(
                        "[{}] SUCCEEDED after {} attempt(s)",
                        e.getName(),
                        e.getNumberOfRetryAttempts() + 1))
                .onError(e -> logger.warn(
                        "[{}] EXHAUSTED all attempts ({}) — activating fallback",
                        e.getName(),
                        e.getNumberOfRetryAttempts() + 1));
    }
}
