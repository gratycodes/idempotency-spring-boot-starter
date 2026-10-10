package io.github.gratycodes.idempotency.web;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:http-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "idempotency.store=jdbc",
        "idempotency.jdbc.initialize-schema=true"})
class H2HttpApiTest extends IdempotentHttpApiContract {}
