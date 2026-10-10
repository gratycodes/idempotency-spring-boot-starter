package io.github.gratycodes.idempotency.core;

import java.time.Duration;

/**
 * Storage for idempotency keys. Implementations must make {@link #tryAcquire} atomic: when many
 * requests race on the same scope and key, exactly one gets {@link AcquireResult.Acquired}.
 *
 * <p>An in-flight claim expires after {@code lockTimeout}, so a crashed request does not block the
 * key forever. A completed key expires after the TTL given to {@link #complete}. Expired keys behave
 * as if they were never used.
 */
public interface IdempotencyStore {

    AcquireResult tryAcquire(String scope, String key, String fingerprint, Duration lockTimeout);

    /**
     * Stores the response and marks the key completed. Returns false if {@code token} no longer owns
     * the key (its lock expired and another request took over); nothing is written in that case.
     */
    boolean complete(String scope, String key, String token, StoredResponse response, Duration ttl);

    /** Deletes the claim so the key can be used again. Returns false if {@code token} no longer owns it. */
    boolean release(String scope, String key, String token);

    /** Removes expired keys. Stores with native expiry (Redis) may do nothing. Returns rows removed. */
    default int purgeExpired() {
        return 0;
    }
}
