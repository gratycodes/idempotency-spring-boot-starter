package io.github.gratycodes.idempotency.core;

/** Receives one event per idempotent request outcome. */
public interface IdempotencyMetrics {

    enum Outcome {
        /** First request for a key: the handler ran. */
        EXECUTED,
        /** Retry of a completed request: stored response replayed. */
        REPLAYED,
        /** Duplicate arrived while the first was still running: 409. */
        CONFLICT,
        /** Key reused with a different request: 422. */
        MISMATCH,
        /** Key required but missing, or malformed: 400. */
        REJECTED,
        /** Handler finished with a status that is not cached; the key was released for retries. */
        RELEASED,
        /** Handler finished after its lock expired and another request took the key; response not stored. */
        LOCK_LOST
    }

    void record(Outcome outcome);

    default void webhook(String provider, boolean firstDelivery) {}

    IdempotencyMetrics NOOP = outcome -> {};
}
