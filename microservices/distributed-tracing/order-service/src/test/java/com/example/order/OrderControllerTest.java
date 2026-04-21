package com.example.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // RestTemplate is defined in OrderServiceApp, not the controller — mock it
    // so the controller's constructor can be satisfied without a real HTTP call.
    @MockBean
    private RestTemplate restTemplate;

    @Test
    void placeOrder_callsInventoryAndReturnsConfirmation() throws Exception {
        when(restTemplate.getForObject(
                "http://localhost:9082/inventory/order-1", String.class))
            .thenReturn("Item order-1: 42 units in stock");

        mockMvc.perform(get("/orders/order-1"))
               .andExpect(status().isOk())
               .andExpect(content().string(
                   "Order #order-1 confirmed. Stock: Item order-1: 42 units in stock"));
    }

    /*
     * Why no tracing assertion here?
     *
     * @WebMvcTest runs with sampling.probability=0.0 (see test/resources/application.properties),
     * so Micrometer Tracing creates no active span during tests — MDC keys traceId/spanId stay
     * empty. Asserting MDC content from the test thread would always see blank values.
     *
     * To observe tracing in action, start the full stack (docker compose up -d) and run
     * both services, then hit GET http://localhost:9081/orders/order-1. The log line will
     * show [order-service,<traceId>,<spanId>] and the span (plus its child from
     * inventory-service) appears in Grafana Tempo, linked by the same traceId.
     */
}
