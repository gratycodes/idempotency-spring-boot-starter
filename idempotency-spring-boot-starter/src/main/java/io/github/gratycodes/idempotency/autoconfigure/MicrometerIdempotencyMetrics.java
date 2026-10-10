package io.github.gratycodes.idempotency.autoconfigure;

import io.github.gratycodes.idempotency.core.IdempotencyMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Counters {@code idempotency.requests{outcome}} (executed, replayed, conflict, mismatch, rejected,
 * released, lock_lost) and {@code idempotency.webhooks{provider,outcome}} (processed, duplicate).
 */
public class MicrometerIdempotencyMetrics implements IdempotencyMetrics {

    private final MeterRegistry registry;
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public MicrometerIdempotencyMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("idempotency.requests")
                    .description("Requests that carried an idempotency key, by outcome")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
    }

    @Override
    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }

    @Override
    public void webhook(String provider, boolean firstDelivery) {
        registry.counter("idempotency.webhooks", "provider", provider, "outcome",
                firstDelivery ? "processed" : "duplicate").increment();
    }
}
