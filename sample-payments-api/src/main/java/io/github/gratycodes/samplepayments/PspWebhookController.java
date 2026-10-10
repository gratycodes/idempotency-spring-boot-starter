package io.github.gratycodes.samplepayments;

import io.github.gratycodes.idempotency.webhook.WebhookInbox;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement webhooks from a (fake) payment service provider. Providers deliver at least once, so the
 * same event can arrive twice; the inbox makes sure the ledger entry is written once.
 * Signature verification is omitted to keep the sample small; a real endpoint must verify it first.
 */
@RestController
class PspWebhookController {

    record PspEvent(String id, String type, String paymentId, long amount) {}

    private final WebhookInbox inbox;
    private final JdbcTemplate jdbc;

    PspWebhookController(WebhookInbox inbox, JdbcTemplate jdbc) {
        this.inbox = inbox;
        this.jdbc = jdbc;
    }

    @PostMapping("/webhooks/psp")
    Map<String, String> receive(@RequestBody PspEvent event) {
        boolean processed = inbox.processOnce("fakepsp", event.id(), () -> {
            if ("payment.settled".equals(event.type())) {
                jdbc.update("UPDATE payments SET status = 'settled' WHERE id = ?", event.paymentId());
                jdbc.update("INSERT INTO ledger_entries (event_id, payment_id, amount) VALUES (?, ?, ?)",
                        event.id(), event.paymentId(), event.amount());
            }
        });
        // 200 either way: the provider must stop retrying a duplicate.
        return Map.of("event", event.id(), "result", processed ? "processed" : "duplicate");
    }
}
