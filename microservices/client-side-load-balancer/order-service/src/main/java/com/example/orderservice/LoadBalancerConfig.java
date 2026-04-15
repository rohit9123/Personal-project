package com.example.orderservice;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.RandomLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Switches the load balancer algorithm from the default Round Robin to Random
 * for the service this config is registered against (inventory-service).
 *
 * NOT annotated with @Configuration — Spring must not pick this up via
 * component scan, or it will apply globally. It's registered explicitly via
 * @LoadBalancerClient(configuration = LoadBalancerConfig.class) in AppConfig.
 *
 * To implement a custom algorithm, see CustomLoadBalancer.java.
 */
public class LoadBalancerConfig {

    @Bean
    public ReactorLoadBalancer<ServiceInstance> randomLoadBalancer(
            Environment environment,
            LoadBalancerClientFactory loadBalancerClientFactory) {

        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);

        return new RandomLoadBalancer(
            loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class),
            name
        );
    }
}
