package com.example.kafkaproducer.model;

public class Order {

    private String orderId;
    private String region;   // EU | US | APAC  — used as the record KEY by RegionPartitioner
    private String item;
    private int quantity;
    private double price;

    public Order() {}

    public Order(String orderId, String region, String item, int quantity, double price) {
        this.orderId = orderId;
        this.region = region;
        this.item = item;
        this.quantity = quantity;
        this.price = price;
    }

    public String getOrderId()              { return orderId; }
    public void   setOrderId(String v)      { this.orderId = v; }
    public String getRegion()               { return region; }
    public void   setRegion(String v)       { this.region = v; }
    public String getItem()                 { return item; }
    public void   setItem(String v)         { this.item = v; }
    public int    getQuantity()             { return quantity; }
    public void   setQuantity(int v)        { this.quantity = v; }
    public double getPrice()                { return price; }
    public void   setPrice(double v)        { this.price = v; }

    @Override
    public String toString() {
        return String.format("Order{id=%s, region=%s, item=%s, qty=%d, price=%.2f}",
                orderId, region, item, quantity, price);
    }
}
