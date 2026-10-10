package io.github.gratycodes.idempotency.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Names the namespace a key lives in, usually the tenant or API client. Two clients that happen to
 * pick the same key must not see each other's responses. Declare your own bean to replace the default.
 */
@FunctionalInterface
public interface IdempotencyScopeResolver {

    String resolve(HttpServletRequest request);
}
