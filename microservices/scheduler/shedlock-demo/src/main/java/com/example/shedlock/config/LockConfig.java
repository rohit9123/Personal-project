package com.example.shedlock.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
// defaultLockAtMostFor = global failsafe TTL applied to any @SchedulerLock
// that does not specify its own lockAtMostFor.
// PT10M = 10 minutes: if a pod crashes mid-job, the lock expires after 10 min.
public class LockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                // usingDbTime(): use the DB server's clock for lock_until comparisons.
                // Critical in multi-region clusters where pod clocks drift by seconds —
                // all pods agree on one clock (the DB), so "is the lock expired?" is consistent.
                .usingDbTime()
                .build()
        );
    }
}
