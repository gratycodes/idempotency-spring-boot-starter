package io.github.gratycodes.idempotency.core;

import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transport-independent idempotency rules on top of an {@link IdempotencyStore}. The servlet layer
 * calls {@link #begin} before the handler and {@link #finish} or {@link #abort} after it.
 */
public class IdempotencyEngine {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyEngine.class);

    private final IdempotencyStore store;
    private final ResponseCachePolicy cachePolicy;
    private final Duration lockTimeout;
    private final IdempotencyMetrics metrics;

    public IdempotencyEngine(IdempotencyStore store, ResponseCachePolicy cachePolicy, Duration lockTimeout,
            IdempotencyMetrics metrics) {
        this.store = Objects.requireNonNull(store, "store");
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
        this.metrics = metrics == null ? IdempotencyMetrics.NOOP : metrics;
    }

    public sealed interface Decision {
        /** Run the handler, then call finish or abort with this token. */
        record Proceed(String token) implements Decision {}

        record Replay(StoredResponse response) implements Decision {}

        record InProgress() implements Decision {}

        record Mismatch() implements Decision {}
    }

    public Decision begin(String scope, String key, String fingerprint) {
        AcquireResult result = store.tryAcquire(scope, key, fingerprint, lockTimeout);
        Decision decision = switch (result) {
            case AcquireResult.Acquired acquired -> new Decision.Proceed(acquired.token());
            case AcquireResult.InFlight inFlight -> fingerprint.equals(inFlight.fingerprint())
                    ? new Decision.InProgress()
                    : new Decision.Mismatch();
            case AcquireResult.Completed completed -> fingerprint.equals(completed.fingerprint())
                    ? new Decision.Replay(completed.response())
                    : new Decision.Mismatch();
        };
        metrics.record(switch (decision) {
            case Decision.Proceed p -> IdempotencyMetrics.Outcome.EXECUTED;
            case Decision.Replay r -> IdempotencyMetrics.Outcome.REPLAYED;
            case Decision.InProgress i -> IdempotencyMetrics.Outcome.CONFLICT;
            case Decision.Mismatch m -> IdempotencyMetrics.Outcome.MISMATCH;
        });
        return decision;
    }

    /** Stores the response for replay, or releases the key if the status should not be cached. */
    public void finish(String scope, String key, String token, StoredResponse response, Duration ttl) {
        if (!cachePolicy.shouldCache(response.status())) {
            store.release(scope, key, token);
            metrics.record(IdempotencyMetrics.Outcome.RELEASED);
            return;
        }
        if (!store.complete(scope, key, token, response, ttl)) {
            log.warn("Idempotency lock for key '{}' expired before the handler finished; the response was "
                    + "not stored. Raise idempotency.lock-timeout above your slowest request.", key);
            metrics.record(IdempotencyMetrics.Outcome.LOCK_LOST);
        }
    }

    /** The handler threw; release the key so the client can retry. */
    public void abort(String scope, String key, String token) {
        store.release(scope, key, token);
        metrics.record(IdempotencyMetrics.Outcome.RELEASED);
    }

    public void rejected() {
        metrics.record(IdempotencyMetrics.Outcome.REJECTED);
    }
}
