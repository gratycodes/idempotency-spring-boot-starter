package io.github.gratycodes.idempotency.core;

import java.util.Set;

/**
 * Decides which completed responses are stored for replay. Anything not stored releases the key so
 * the client can retry. By default 5xx, 408 and 429 are not stored: they describe a transient state,
 * not the outcome of the operation.
 */
public record ResponseCachePolicy(boolean cacheServerErrors, Set<Integer> neverCache) {

    public ResponseCachePolicy {
        neverCache = Set.copyOf(neverCache);
    }

    public static ResponseCachePolicy defaults() {
        return new ResponseCachePolicy(false, Set.of(408, 429));
    }

    public boolean shouldCache(int status) {
        if (status < 200 || neverCache.contains(status)) {
            return false;
        }
        return status < 500 || cacheServerErrors;
    }
}
