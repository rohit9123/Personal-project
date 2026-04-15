package com.example.orderservice;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.Request;
import org.springframework.cloud.loadbalancer.core.Response;
import org.springframework.cloud.loadbalancer.core.DefaultResponse;
import org.springframework.cloud.loadbalancer.core.EmptyResponse;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom Load Balancer — explicit Round Robin implementation.
 *
 * Shows the full interface you implement when built-in algorithms aren't enough
 * (e.g., weighted round-robin, least-connections, zone-aware routing).
 *
 * To activate:
 *   In LoadBalancerConfig, replace RandomLoadBalancer with:
 *
 *     return new CustomLoadBalancer(
 *         loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class),
 *         name
 *     );
 */
public class CustomLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final String serviceId;
    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
    private final AtomicInteger position = new AtomicInteger(0);

    public CustomLoadBalancer(ObjectProvider<ServiceInstanceListSupplier> supplierProvider,
                              String serviceId) {
        this.supplierProvider = supplierProvider;
        this.serviceId = serviceId;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable();
        return supplier.get(request)
                .next()
                .map(this::pickInstance);
    }

    private Response<ServiceInstance> pickInstance(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            return new EmptyResponse();
        }
        // Increment and wrap — thread-safe via AtomicInteger
        int index = Math.abs(position.getAndIncrement()) % instances.size();
        return new DefaultResponse(instances.get(index));
    }
}
