package com.example.actuatordemo;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * InfoContributor — adds custom data to /actuator/info.
 *
 * By default /actuator/info is empty. You can populate it two ways:
 *   1. Static values in application.yml under "info:" prefix (shown in application.yml)
 *   2. Programmatically via InfoContributor beans (this class)
 *
 * Both sources are merged together in the /actuator/info response.
 */
@Component
public class AppInfoContributor implements InfoContributor {

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("runtime", Map.of(
                "javaVersion",  System.getProperty("java.version"),
                "processors",   Runtime.getRuntime().availableProcessors(),
                "maxMemoryMB",  Runtime.getRuntime().maxMemory() / (1024 * 1024)
        ));

        builder.withDetail("team", Map.of(
                "name",  "backend",
                "email", "backend@example.com"
        ));
    }
}
