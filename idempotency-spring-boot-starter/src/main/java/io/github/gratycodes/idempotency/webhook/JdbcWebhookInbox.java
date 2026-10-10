package io.github.gratycodes.idempotency.webhook;

import io.github.gratycodes.idempotency.core.IdempotencyMetrics;
import java.time.Clock;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook inbox backed by a table with a primary key on (provider, event_id) and
 * {@code INSERT ... ON CONFLICT DO NOTHING}. On PostgreSQL a concurrent duplicate blocks on the
 * first delivery's uncommitted row and then inserts nothing, so the handler runs at most once.
 */
public class JdbcWebhookInbox implements WebhookInbox {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final IdempotencyMetrics metrics;
    private final String insertSql;

    public JdbcWebhookInbox(DataSource dataSource, TransactionTemplate tx, String table, Clock clock,
            IdempotencyMetrics metrics) {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = tx;
        this.clock = clock;
        this.metrics = metrics == null ? IdempotencyMetrics.NOOP : metrics;
        this.insertSql = "INSERT INTO " + table + " (provider, event_id, received_at) VALUES (?, ?, ?)"
                + " ON CONFLICT DO NOTHING";
    }

    @Override
    public boolean processOnce(String provider, String eventId, Runnable handler) {
        Boolean ran = tx.execute(status -> {
            if (!insert(provider, eventId)) {
                return false;
            }
            handler.run();
            return true;
        });
        boolean first = Boolean.TRUE.equals(ran);
        metrics.webhook(provider, first);
        return first;
    }

    @Override
    public boolean markProcessed(String provider, String eventId) {
        boolean first = insert(provider, eventId);
        metrics.webhook(provider, first);
        return first;
    }

    private boolean insert(String provider, String eventId) {
        try {
            return jdbc.update(insertSql, provider, eventId, clock.millis()) == 1;
        } catch (DuplicateKeyException e) {
            // Databases without ON CONFLICT semantics under concurrency (H2) report the duplicate instead.
            return false;
        }
    }
}
