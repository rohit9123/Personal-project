package com.example.paymentservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment Service controller.
 *
 * The "slow" query param simulates a slow downstream — use it to
 * fill the order-service's payment bulkhead and see rejections in action.
 *
 * Hit: GET /payment/42?slow=true   → sleeps 5s (clogs the bulkhead)
 * Hit: GET /payment/42             → responds immediately
 */
@RestController
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    @GetMapping("/payment/{orderId}")
    public String processPayment(
            @PathVariable String orderId,
            @RequestParam(defaultValue = "false") boolean slow) throws InterruptedException {

        if (slow) {
            logger.info("Processing payment for order {} — simulating slow response (5s)", orderId);
            Thread.sleep(5000);
        } else {
            logger.info("Processing payment for order {}", orderId);
        }

        return "Payment APPROVED for order " + orderId;
    }
}
