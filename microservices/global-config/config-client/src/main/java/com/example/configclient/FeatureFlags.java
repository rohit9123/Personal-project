package com.example.configclient;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Demonstrates a second @ConfigurationProperties with its own prefix.
 * Relaxed binding maps YAML 'dark-mode' → Java 'darkMode' automatically.
 */
@ConfigurationProperties(prefix = "feature")
public class FeatureFlags {

    private boolean darkMode;
    private boolean beta;

    public boolean isDarkMode()             { return darkMode; }
    public void setDarkMode(boolean d)      { this.darkMode = d; }

    public boolean isBeta()                 { return beta; }
    public void setBeta(boolean b)          { this.beta = b; }
}
