-- ShedLock requires this table to coordinate distributed locks.
-- Each row represents one named lock (one row per unique @SchedulerLock name).
-- The row persists between runs — it is updated, not deleted.
--
-- Columns:
--   name       — unique job name (PRIMARY KEY)
--   lock_until — when the lock expires (failsafe if pod crashes)
--   locked_at  — when this pod acquired the lock
--   locked_by  — pod hostname/UUID that holds the lock
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
