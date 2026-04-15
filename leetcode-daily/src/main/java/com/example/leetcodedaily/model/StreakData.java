package com.example.leetcodedaily.model;

import java.time.LocalDate;

public class StreakData {

    private String lastSolvedDate;  // ISO format "2026-04-16", null if never
    private int currentStreak;
    private int totalDays;

    public StreakData() {}

    public boolean isCompletedToday() {
        return LocalDate.now().toString().equals(lastSolvedDate);
    }

    public String getLastSolvedDate() { return lastSolvedDate; }
    public void setLastSolvedDate(String lastSolvedDate) { this.lastSolvedDate = lastSolvedDate; }

    public int getCurrentStreak() { return currentStreak; }
    public void setCurrentStreak(int currentStreak) { this.currentStreak = currentStreak; }

    public int getTotalDays() { return totalDays; }
    public void setTotalDays(int totalDays) { this.totalDays = totalDays; }
}
