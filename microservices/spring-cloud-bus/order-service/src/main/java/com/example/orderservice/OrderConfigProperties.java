package com.example.orderservice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * Binds to 'order.*' properties served by Config Server from config-repo/order-service.yml.
 *
 * @RefreshScope: this bean is re-created when a bus refresh event fires.
 * Without @RefreshScope, the values are frozen at startup — editing config-repo
 * and calling /actuator/busrefresh would have no effect on this bean.
 */
@RefreshScope
@ConfigurationProperties(prefix = "order")
public class OrderConfigProperties {

    private String greeting = "default greeting";
    private int    maxItems = 10;

    public String getGreeting()              { return greeting; }
    public void   setGreeting(String g)      { this.greeting = g; }

    public int    getMaxItems()              { return maxItems; }
    public void   setMaxItems(int m)         { this.maxItems = m; }
}
