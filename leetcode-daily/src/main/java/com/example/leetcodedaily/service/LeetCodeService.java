package com.example.leetcodedaily.service;

import com.example.leetcodedaily.config.LeetCodeProperties;
import com.example.leetcodedaily.model.Problem;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LeetCodeService {

    private static final Logger log = LoggerFactory.getLogger(LeetCodeService.class);
    private static final String PROBLEMS_URL = "https://leetcode.com/api/problems/all/";
    private static final String GRAPHQL_URL  = "https://leetcode.com/graphql";

    private final RestTemplate restTemplate;
    private final LeetCodeProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Simple daily cache — problems list changes rarely
    private List<Problem> cachedProblems = Collections.emptyList();
    private LocalDate cacheDate = null;

    public LeetCodeService(RestTemplate restTemplate, LeetCodeProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    /**
     * Returns `count` random unsolved problems, optionally filtered by difficulty.
     * Each returned problem is enriched with topic tags (if csrf-token is configured).
     *
     * @throws IllegalStateException if the session cookie is not configured or is expired
     */
    public List<Problem> getPicks(String difficulty, int count) {
        if (!props.isConfigured()) {
            throw new IllegalStateException(
                "LEETCODE_SESSION not set. Paste your session cookie into application.yml.");
        }

        refreshCacheIfNeeded();

        List<Problem> pool = cachedProblems.stream()
            .filter(p -> difficulty == null || difficulty.isBlank()
                      || difficulty.equalsIgnoreCase(p.getDifficulty()))
            .collect(Collectors.toList());

        Collections.shuffle(pool);

        List<Problem> picks = pool.subList(0, Math.min(count, pool.size()));

        if (props.hasCsrfToken()) {
            picks.forEach(p -> p.setTags(fetchTags(p.getTitleSlug())));
        }

        return picks;
    }

    // -------------------------------------------------------------------------
    // Cache management
    // -------------------------------------------------------------------------

    private void refreshCacheIfNeeded() {
        if (cacheDate != null && cacheDate.equals(LocalDate.now()) && !cachedProblems.isEmpty()) {
            return;
        }
        cachedProblems = fetchUnsolvedProblems();
        cacheDate = LocalDate.now();
        log.info("Loaded {} unsolved problems from LeetCode API", cachedProblems.size());
    }

    // -------------------------------------------------------------------------
    // LeetCode REST API — all problems with solved status
    // -------------------------------------------------------------------------

    private List<Problem> fetchUnsolvedProblems() {
        HttpEntity<Void> request = new HttpEntity<>(buildHeaders());

        // Fetch as String first so we can detect HTML (session redirect) before Jackson tries to parse it
        ResponseEntity<String> raw = restTemplate.exchange(
            PROBLEMS_URL, HttpMethod.GET, request, String.class
        );

        String body = raw.getBody();
        if (body == null || !body.stripLeading().startsWith("{")) {
            throw new IllegalStateException(
                "Session expired or invalid — LeetCode returned a login page instead of JSON. "
                + "Copy a fresh LEETCODE_SESSION cookie and update application.yml.");
        }

        ProblemsResponse parsed;
        try {
            parsed = objectMapper.readValue(body, ProblemsResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse LeetCode response: " + e.getMessage());
        }

        if (parsed.statStatusPairs == null) {
            throw new IllegalStateException("Empty response from LeetCode API. Session may be expired.");
        }

        return parsed.statStatusPairs.stream()
            .filter(p -> p.stat != null && p.difficulty != null)  // guard malformed entries
            .filter(p -> !"ac".equals(p.status))    // skip solved
            .filter(p -> !p.paidOnly)               // skip premium-only
            .filter(p -> p.stat.frontendQuestionId > 0)
            .map(p -> new Problem(
                p.stat.frontendQuestionId,
                p.stat.title,
                p.stat.titleSlug,
                mapDifficulty(p.difficulty.level)
            ))
            .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // LeetCode GraphQL API — topic tags for a specific problem
    // -------------------------------------------------------------------------

    private List<String> fetchTags(String titleSlug) {
        try {
            String query = "query questionData($titleSlug: String!) "
                + "{ question(titleSlug: $titleSlug) { topicTags { name } } }";

            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("variables", Map.of("titleSlug", titleSlug));

            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-csrftoken", props.getCsrfToken());

            ResponseEntity<String> response = restTemplate.postForEntity(
                GRAPHQL_URL, new HttpEntity<>(body, headers), String.class
            );

            JsonNode tagsNode = objectMapper.readTree(response.getBody())
                .path("data").path("question").path("topicTags");

            List<String> tags = new ArrayList<>();
            if (tagsNode.isArray()) {
                tagsNode.forEach(t -> tags.add(t.path("name").asText()));
            }
            return tags;

        } catch (Exception e) {
            log.warn("Could not fetch tags for {}: {}", titleSlug, e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE,
            "LEETCODE_SESSION=" + props.getSession()
            + (props.hasCsrfToken() ? "; csrftoken=" + props.getCsrfToken() : ""));
        headers.set(HttpHeaders.USER_AGENT,
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.set(HttpHeaders.REFERER, "https://leetcode.com/");
        // LeetCode returns Content-Type: text/html even for JSON responses.
        // Sending Accept: application/json makes the body actually come back as JSON.
        headers.set(HttpHeaders.ACCEPT, "application/json");
        return headers;
    }

    private String mapDifficulty(int level) {
        return switch (level) {
            case 1 -> "Easy";
            case 2 -> "Medium";
            case 3 -> "Hard";
            default -> "Unknown";
        };
    }

    // -------------------------------------------------------------------------
    // Private DTOs — map the LeetCode REST API response
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ProblemsResponse {
        @JsonProperty("stat_status_pairs")
        public List<StatStatusPair> statStatusPairs;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class StatStatusPair {
        public Stat stat;
        public Difficulty difficulty;
        @JsonProperty("paid_only") public boolean paidOnly;
        public String status; // "ac" | "notac" | null
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Stat {
        @JsonProperty("frontend_question_id") public int frontendQuestionId;
        @JsonProperty("question__title")      public String title;
        @JsonProperty("question__title_slug") public String titleSlug;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Difficulty {
        public int level; // 1=Easy 2=Medium 3=Hard
    }
}
