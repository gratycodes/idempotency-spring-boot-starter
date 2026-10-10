package io.github.gratycodes.idempotency.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "idempotency.store=memory")
class InMemoryHttpApiTest extends IdempotentHttpApiContract {

    @Autowired
    MeterRegistry registry;

    @Test
    void outcomesAreCountedInMicrometer() throws Exception {
        double executed = count("executed");
        double replayed = count("replayed");
        double mismatch = count("mismatch");

        post("/payments", key, "tenant-a", PAYMENT);
        post("/payments", key, "tenant-a", PAYMENT);
        post("/payments", key, "tenant-a", "{\"amount\":1}");

        assertThat(count("executed")).isEqualTo(executed + 1);
        assertThat(count("replayed")).isEqualTo(replayed + 1);
        assertThat(count("mismatch")).isEqualTo(mismatch + 1);
    }

    private double count(String outcome) {
        return registry.get("idempotency.requests").tag("outcome", outcome).counter().count();
    }
}
