package io.github.gratycodes.idempotency.store;

import io.github.gratycodes.idempotency.core.AcquireResult;
import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.core.StoredResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-JVM store for tests and local development. Not shared between instances, and lost on
 * restart, so do not use it behind a load balancer.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(String fingerprint, String token, long lockedUntil, StoredResponse response, long expiresAt) {
        boolean completed() {
            return response != null;
        }

        boolean expired(long now) {
            return completed() ? expiresAt <= now : lockedUntil <= now;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public AcquireResult tryAcquire(String scope, String key, String fingerprint, Duration lockTimeout) {
        AtomicReference<AcquireResult> result = new AtomicReference<>();
        entries.compute(id(scope, key), (id, existing) -> {
            long now = clock.millis();
            if (existing == null || existing.expired(now)) {
                String token = UUID.randomUUID().toString();
                result.set(new AcquireResult.Acquired(token));
                return new Entry(fingerprint, token, now + lockTimeout.toMillis(), null, 0);
            }
            result.set(existing.completed()
                    ? new AcquireResult.Completed(existing.fingerprint(), existing.response())
                    : new AcquireResult.InFlight(existing.fingerprint()));
            return existing;
        });
        return result.get();
    }

    @Override
    public boolean complete(String scope, String key, String token, StoredResponse response, Duration ttl) {
        boolean[] done = {false};
        entries.computeIfPresent(id(scope, key), (id, e) -> {
            if (e.completed() || !e.token().equals(token)) {
                return e;
            }
            done[0] = true;
            return new Entry(e.fingerprint(), null, 0, response, clock.millis() + ttl.toMillis());
        });
        return done[0];
    }

    @Override
    public boolean release(String scope, String key, String token) {
        boolean[] done = {false};
        entries.computeIfPresent(id(scope, key), (id, e) -> {
            if (!e.completed() && e.token().equals(token)) {
                done[0] = true;
                return null;
            }
            return e;
        });
        return done[0];
    }

    @Override
    public int purgeExpired() {
        long now = clock.millis();
        int before = entries.size();
        entries.values().removeIf(e -> e.expired(now));
        return before - entries.size();
    }

    private static String id(String scope, String key) {
        return scope.length() + ":" + scope + ":" + key;
    }
}
