package com.example.orderservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Approach 1: @LoadBalanced RestTemplate
 *
 * The RestTemplate bean is annotated @LoadBalanced in AppConfig, which installs
 * a LoadBalancerInterceptor on every request. When this call is made:
 *
 *   restTemplate.getForObject("http://inventory-service/inventory/" + id, ...)
 *
 * The interceptor:
 *   1. Extracts the host "inventory-service" from the URL
 *   2. Asks LoadBalancer: "which instance of inventory-service should I call?"
 *   3. Replaces "inventory-service" with the chosen host:port (e.g. localhost:9081)
 *   4. Forwards the actual HTTP request
 */
@RestController
@RequestMapping("/order/resttemplate")
public class RestTemplateOrderController {

    private static final Logger logger = LoggerFactory.getLogger(RestTemplateOrderController.class);

    private final RestTemplate restTemplate;

    public RestTemplateOrderController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/{id}")
    public String placeOrder(@PathVariable String id) {
        logger.info("Placing order {} via RestTemplate", id);

        String stock = restTemplate.getForObject(
            "http://inventory-service/inventory/" + id,
            String.class
        );

        return "Order " + id + " | " + stock;
    }
}
