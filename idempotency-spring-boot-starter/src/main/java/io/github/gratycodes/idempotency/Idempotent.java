package io.github.gratycodes.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring MVC handler (or every handler in a controller) as idempotent.
 *
 * <p>When a request to the handler carries an {@code Idempotency-Key} header, the handler runs at
 * most once per key and scope. Retries with the same key and the same request get the stored
 * response back; a retry with the same key but a different request gets {@code 422}; a retry while
 * the first request is still running gets {@code 409} with {@code Retry-After}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** Whether a request without a key is rejected with 400. DEFAULT uses {@code idempotency.require-key}. */
    KeyRequirement key() default KeyRequirement.DEFAULT;

    /**
     * How long a completed response is kept for replay, as an ISO-8601 or Spring duration
     * ({@code PT1H}, {@code 30m}). Empty uses {@code idempotency.ttl}.
     */
    String ttl() default "";

    enum KeyRequirement {
        DEFAULT,
        REQUIRED,
        OPTIONAL
    }
}
