package com.example.configserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

// Disables Bus (no RabbitMQ needed) and uses a classpath config-repo for the test.
// The classpath:/config-repo path resolves to src/test/resources/config-repo/ —
// we create a minimal stub file there so Config Server has something to serve.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.cloud.bus.enabled=false",
    "spring.cloud.config.server.native.search-locations=classpath:/config-repo"
})
class ConfigServerAppTest {
    @Test
    void contextLoads() {}
}
