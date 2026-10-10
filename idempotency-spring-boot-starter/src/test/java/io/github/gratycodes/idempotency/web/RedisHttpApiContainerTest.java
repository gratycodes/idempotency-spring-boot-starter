package io.github.gratycodes.idempotency.web;

import io.github.gratycodes.idempotency.support.ExternalServices;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "idempotency.store=redis")
class RedisHttpApiContainerTest extends IdempotentHttpApiContract {

    @BeforeAll
    static void requireRedis() {
        ExternalServices.redis();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> ExternalServices.redis().host());
        registry.add("spring.data.redis.port", () -> ExternalServices.redis().port());
    }
}
