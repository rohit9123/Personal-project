package com.example.leetcodedaily.model;

import java.util.List;

public class Problem {

    private int id;
    private String title;
    private String titleSlug;
    private String difficulty;    // "Easy", "Medium", "Hard"
    private List<String> tags;    // populated via GraphQL if csrf-token is set

    public Problem(int id, String title, String titleSlug, String difficulty) {
        this.id = id;
        this.title = title;
        this.titleSlug = titleSlug;
        this.difficulty = difficulty;
        this.tags = List.of();
    }

    public String getUrl() {
        return "https://leetcode.com/problems/" + titleSlug + "/";
    }

    public int getId() { return id; }
    public String getTitle() { return title; }
    public String getTitleSlug() { return titleSlug; }
    public String getDifficulty() { return difficulty; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
