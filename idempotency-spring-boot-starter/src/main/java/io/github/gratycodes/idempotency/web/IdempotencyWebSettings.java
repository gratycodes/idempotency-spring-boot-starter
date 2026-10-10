package io.github.gratycodes.idempotency.web;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** The web-facing settings, decoupled from Spring Boot property binding. */
public record IdempotencyWebSettings(
        String headerName,
        boolean requireKey,
        int maxKeyLength,
        Set<String> methods,
        List<String> includePaths,
        Duration ttl,
        Duration retryAfter,
        List<String> replayHeaders,
        List<String> fingerprintHeaders) {

    public IdempotencyWebSettings {
        methods = methods.stream().map(m -> m.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        includePaths = List.copyOf(includePaths);
        replayHeaders = List.copyOf(replayHeaders);
        fingerprintHeaders = List.copyOf(fingerprintHeaders);
    }
}
