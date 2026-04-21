package com.example.configclient;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/config")
public class ConfigController {

    private final AppProperties app;
    private final FeatureFlags features;
    private final DynamicConfig dynamic;
    private final Environment env;

    public ConfigController(AppProperties app,
                            FeatureFlags features,
                            DynamicConfig dynamic,
                            Environment env) {
        this.app = app;
        this.features = features;
        this.dynamic = dynamic;
        this.env = env;
    }

    /**
     * GET /config
     * Shows values from @ConfigurationProperties beans.
     * These are fetched from the Config Server at startup.
     * They do NOT change on /actuator/refresh unless also annotated @RefreshScope.
     */
    @GetMapping
    public Map<String, Object> all() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeProfiles", Arrays.asList(env.getActiveProfiles()));

        Map<String, Object> appMap = new LinkedHashMap<>();
        appMap.put("name",          app.getName());
        appMap.put("version",       app.getVersion());
        appMap.put("maxRetries",    app.getMaxRetries());
        appMap.put("mail.host",     app.getMail().getHost());
        appMap.put("mail.port",     app.getMail().getPort());
        appMap.put("mail.username", app.getMail().getUsername());
        result.put("app (@ConfigurationProperties)", appMap);

        Map<String, Object> featureMap = new LinkedHashMap<>();
        featureMap.put("darkMode", features.isDarkMode());
        featureMap.put("beta",     features.isBeta());
        result.put("features (@ConfigurationProperties)", featureMap);

        return result;
    }

    /**
     * GET /config/dynamic
     * Shows values from the @RefreshScope bean.
     *
     * Demo flow for zero-downtime config update:
     *   1. GET /config/dynamic                           ← see current values
     *   2. Edit config-client-dev.yml on the server side (e.g. set beta: false)
     *   3. POST http://localhost:8080/actuator/refresh   ← returns ["feature.beta"]
     *   4. GET /config/dynamic                           ← DynamicConfig has new value
     *      (no restart — the @RefreshScope proxy swapped the bean instance)
     */
    @GetMapping("/dynamic")
    public Map<String, Object> dynamic() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source",      "@RefreshScope — re-created on POST /actuator/refresh");
        result.put("appName",     dynamic.getAppName());
        result.put("betaEnabled", dynamic.isBetaEnabled());
        return result;
    }
}
