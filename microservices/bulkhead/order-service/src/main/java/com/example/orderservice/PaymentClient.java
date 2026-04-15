package com.example.orderservice;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Calls payment-service, protected by a ThreadPoolBulkhead.
 *
 * TYPE.THREADPOOL means Resilience4j executes this method on a dedicated
 * thread pool (defined in application.yml under thread-pool-bulkhead.instances.payment).
 * The caller's thread is freed immediately; the method runs asynchronously
 * and returns a CompletableFuture (handled by Spring's async support here).
 *
 * When the thread pool + queue are both full, Resilience4j throws
 * BulkheadFullException before even trying to call payment-service.
 */
@Service
public class PaymentClient {

    private static final Logger logger = LoggerFactory.getLogger(PaymentClient.class);

    private final RestTemplate restTemplate;
    private final String paymentServiceUrl;

    public PaymentClient(RestTemplate restTemplate,
                         @Value("${payment.service.url}") String paymentServiceUrl) {
        this.restTemplate = restTemplate;
        this.paymentServiceUrl = paymentServiceUrl;
    }

    @Bulkhead(name = "payment", type = Bulkhead.Type.THREADPOOL, fallbackMethod = "paymentFallback")
    public java.util.concurrent.CompletableFuture<String> processPayment(String orderId, boolean slow) {
        logger.info("Calling payment-service for order {} (slow={})", orderId, slow);
        String url = paymentServiceUrl + "/payment/" + orderId + "?slow=" + slow;
        String response = restTemplate.getForObject(url, String.class);
        return java.util.concurrent.CompletableFuture.completedFuture(response);
    }

    /**
     * Fallback invoked when the ThreadPoolBulkhead is full.
     * Signature must match the protected method plus a Throwable parameter.
     */
    public java.util.concurrent.CompletableFuture<String> paymentFallback(
            String orderId, boolean slow, Throwable t) {
        logger.warn("Payment bulkhead FULL for order {} — rejecting. Reason: {}", orderId, t.getMessage());
        return java.util.concurrent.CompletableFuture.completedFuture(
                "Payment REJECTED (bulkhead full) for order " + orderId);
    }
}
