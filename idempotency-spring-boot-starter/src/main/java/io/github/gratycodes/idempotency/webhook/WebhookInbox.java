package io.github.gratycodes.idempotency.webhook;

/**
 * Deduplicates incoming webhook events by the provider's event id. Providers deliver at least once,
 * so the same event can arrive twice (retries after a timeout, replays from a dashboard).
 */
public interface WebhookInbox {

    /**
     * Records the event and, if it was not seen before, runs {@code handler} in the same database
     * transaction. Returns true if the handler ran, false if this event id was already processed.
     *
     * <p>If the handler throws, the transaction (including the inbox row) rolls back and the exception
     * propagates, so the provider's redelivery is processed normally. Database writes the handler makes
     * through the same transaction manager commit together with the inbox row, so they happen once.
     * Calls to other systems (emails, HTTP) are not covered: they can still repeat if the commit fails
     * after they ran.
     */
    boolean processOnce(String provider, String eventId, Runnable handler);

    /**
     * Inserts the inbox row in the caller's current transaction. Returns false if it already exists.
     * Use this when the handler already runs inside {@code @Transactional}.
     */
    boolean markProcessed(String provider, String eventId);
}
