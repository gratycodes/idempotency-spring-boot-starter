package io.github.gratycodes.idempotency.core;

/** What a store saw when a request tried to claim a key. */
public sealed interface AcquireResult {

    /**
     * This request owns the key now. {@code token} must be passed back to complete or release it, so
     * a request whose lock expired cannot overwrite the work of the request that took over.
     */
    record Acquired(String token) implements AcquireResult {}

    /** Someone else's request is still running under this key. */
    record InFlight(String fingerprint) implements AcquireResult {}

    /** The key already finished; {@code response} is what to replay. */
    record Completed(String fingerprint, StoredResponse response) implements AcquireResult {}
}
