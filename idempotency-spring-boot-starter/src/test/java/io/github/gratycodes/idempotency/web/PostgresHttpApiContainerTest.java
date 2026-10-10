package io.github.gratycodes.idempotency.web;

import io.github.gratycodes.idempotency.support.ExternalServices;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"idempotency.store=jdbc", "idempotency.jdbc.initialize-schema=true"})
class PostgresHttpApiContainerTest extends IdempotentHttpApiContract {

    @BeforeAll
    static void requirePostgres() {
        ExternalServices.postgres();
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> ExternalServices.postgres().url());
        registry.add("spring.datasource.username", () -> ExternalServices.postgres().user());
        registry.add("spring.datasource.password", () -> ExternalServices.postgres().password());
    }
}
