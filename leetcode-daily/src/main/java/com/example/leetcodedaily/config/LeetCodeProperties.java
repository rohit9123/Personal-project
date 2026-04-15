package com.example.leetcodedaily.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leetcode")
public class LeetCodeProperties {

    private String session;
    private String csrfToken;

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public String getCsrfToken() { return csrfToken; }
    public void setCsrfToken(String csrfToken) { this.csrfToken = csrfToken; }

    public boolean hasCsrfToken() {
        return csrfToken != null && !csrfToken.isBlank()
            && !csrfToken.equals("PASTE_YOUR_CSRFTOKEN_HERE");
    }

    public boolean isConfigured() {
        return session != null && !session.isBlank()
            && !session.equals("PASTE_YOUR_LEETCODE_SESSION_HERE");
    }
}
