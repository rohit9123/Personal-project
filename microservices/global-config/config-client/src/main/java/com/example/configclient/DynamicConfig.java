package com.example.configclient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * @RefreshScope — the key to zero-downtime config updates.
 *
 * Problem without it:
 *   @Value fields are injected ONCE at startup and frozen.
 *   Changing a value on the Config Server has no effect until you restart.
 *
 * How @RefreshScope works:
 *   1. This bean is wrapped in a Spring scope proxy.
 *   2. Someone edits config-client-dev.yml on the Config Server.
 *   3. POST /actuator/refresh is called on this service.
 *   4. Spring Cloud Context re-fetches all property sources from the server.
 *   5. The proxy's internal cache is invalidated (real bean destroyed).
 *   6. On the next call, a new instance is created — @Value fields are
 *      re-injected with the fresh values from the server.
 *
 * The caller holds a reference to the proxy, not the real bean.
 * From the caller's perspective nothing changed — but the values are new.
 *
 * Note: @ConfigurationProperties beans are NOT refreshed by default.
 * Add @RefreshScope to them too if you want them to pick up new values.
 */
@Component
@RefreshScope
public class DynamicConfig {

    @Value("${app.name}")
    private String appName;

    @Value("${feature.beta:false}")
    private boolean betaEnabled;

    public String getAppName()      { return appName; }
    public boolean isBetaEnabled()  { return betaEnabled; }
}
