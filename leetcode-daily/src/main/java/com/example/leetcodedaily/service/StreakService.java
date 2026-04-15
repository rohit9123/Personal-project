package com.example.leetcodedaily.service;

import com.example.leetcodedaily.model.StreakData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class StreakService {

    private static final Path STREAK_FILE = Paths.get(
        System.getProperty("user.home"), ".leetcode-daily", "streak.json"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Returns the current streak, resetting it if the user missed yesterday.
     * Does NOT write to disk — only markDone() does.
     */
    public StreakData getStreak() {
        StreakData data = readFile();

        if (data.getLastSolvedDate() != null) {
            LocalDate last = LocalDate.parse(data.getLastSolvedDate());
            long daysAgo = ChronoUnit.DAYS.between(last, LocalDate.now());
            if (daysAgo > 1) {
                // Missed at least one day — streak is broken visually, but
                // don't write back yet (wait for markDone to persist the reset)
                data.setCurrentStreak(0);
            }
        }

        return data;
    }

    /**
     * Records today as a practice day.
     * - First call today: increments or starts streak, saves to disk.
     * - Subsequent calls same day: no-op.
     */
    public void markDone() {
        StreakData data = readFile();
        LocalDate today = LocalDate.now();

        if (today.toString().equals(data.getLastSolvedDate())) {
            return; // already counted today
        }

        long daysAgo = data.getLastSolvedDate() == null ? 2
            : ChronoUnit.DAYS.between(LocalDate.parse(data.getLastSolvedDate()), today);

        data.setCurrentStreak(daysAgo == 1 ? data.getCurrentStreak() + 1 : 1);
        data.setTotalDays(data.getTotalDays() + 1);
        data.setLastSolvedDate(today.toString());

        writeFile(data);
    }

    private StreakData readFile() {
        if (!Files.exists(STREAK_FILE)) {
            return new StreakData();
        }
        try {
            return objectMapper.readValue(STREAK_FILE.toFile(), StreakData.class);
        } catch (IOException e) {
            return new StreakData();
        }
    }

    private void writeFile(StreakData data) {
        try {
            Files.createDirectories(STREAK_FILE.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(STREAK_FILE.toFile(), data);
        } catch (IOException e) {
            // non-critical — streak just won't persist
        }
    }
}
