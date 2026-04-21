package com.example.configclient;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe binding for all properties under the "app" prefix.
 *
 * These values are NOT in config-client/src/main/resources — they come
 * from the Config Server at startup (and refresh on /actuator/refresh).
 *
 * Spring Boot validates them using JSR-303 at context load time —
 * if host is blank or maxRetries is out of range, the app fails fast.
 */
@ConfigurationProperties(prefix = "app")
@Validated
public class AppProperties {

    @NotBlank
    private String name;

    private String version = "0.0.1";

    @Min(1) @Max(10)
    private int maxRetries = 3;

    @Valid
    private Mail mail = new Mail();

    public String getName()        { return name; }
    public void setName(String n)  { this.name = n; }

    public String getVersion()          { return version; }
    public void setVersion(String v)    { this.version = v; }

    public int getMaxRetries()          { return maxRetries; }
    public void setMaxRetries(int r)    { this.maxRetries = r; }

    public Mail getMail()               { return mail; }
    public void setMail(Mail m)         { this.mail = m; }

    public static class Mail {

        @NotBlank
        private String host;

        @Min(1) @Max(65535)
        private int port = 587;

        private String username;

        public String getHost()           { return host; }
        public void setHost(String h)     { this.host = h; }

        public int getPort()              { return port; }
        public void setPort(int p)        { this.port = p; }

        public String getUsername()       { return username; }
        public void setUsername(String u) { this.username = u; }
    }
}
