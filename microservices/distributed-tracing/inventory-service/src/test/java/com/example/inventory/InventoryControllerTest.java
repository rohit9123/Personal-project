package com.example.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getStock_returnsStockInfo() throws Exception {
        mockMvc.perform(get("/inventory/item-7"))
               .andExpect(status().isOk())
               .andExpect(content().string("Item item-7: 42 units in stock"));
    }

    /*
     * Why no tracing assertion here?
     *
     * @WebMvcTest runs with sampling.probability=0.0 (see test/resources/application.properties),
     * so Micrometer Tracing creates no active span during tests — MDC keys traceId/spanId stay
     * empty. Asserting MDC content from the test thread would always see blank values.
     *
     * To observe tracing in action, start the full stack (docker compose up -d) and run
     * both services, then hit GET http://localhost:9082/inventory/item-1. The log line will
     * show [inventory-service,<traceId>,<spanId>] and the span appears in Grafana Tempo.
     */
}
