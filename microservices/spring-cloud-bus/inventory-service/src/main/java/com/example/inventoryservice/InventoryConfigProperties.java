package com.example.inventoryservice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * Binds to 'inventory.*' properties from config-repo/inventory-service.yml.
 * @RefreshScope ensures values are updated when a bus refresh event fires.
 */
@RefreshScope
@ConfigurationProperties(prefix = "inventory")
public class InventoryConfigProperties {

    private String greeting        = "default greeting";
    private int    stockThreshold  = 5;

    public String getGreeting()               { return greeting; }
    public void   setGreeting(String g)       { this.greeting = g; }

    public int    getStockThreshold()         { return stockThreshold; }
    public void   setStockThreshold(int t)    { this.stockThreshold = t; }
}
