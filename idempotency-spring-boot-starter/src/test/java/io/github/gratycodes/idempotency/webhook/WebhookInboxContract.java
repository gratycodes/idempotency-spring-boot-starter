package io.github.gratycodes.idempotency.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gratycodes.idempotency.support.MutableClock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

abstract class WebhookInboxContract {

    abstract DataSource dataSource();

    private JdbcWebhookInbox inbox;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private String eventId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
        inbox = new JdbcWebhookInbox(dataSource(), tx, "webhook_inbox", new MutableClock(), null);
        jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("CREATE TABLE IF NOT EXISTS ledger_entries (event_id VARCHAR(255), amount BIGINT)");
        eventId = "evt_" + UUID.randomUUID();
    }

    @Test
    void secondDeliveryOfTheSameEventIsSkipped() {
        AtomicInteger runs = new AtomicInteger();
        assertThat(inbox.processOnce("psp", eventId, runs::incrementAndGet)).isTrue();
        assertThat(inbox.processOnce("psp", eventId, runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(1);
    }

    @Test
    void sameEventIdFromAnotherProviderIsADifferentEvent() {
        assertThat(inbox.processOnce("psp-a", eventId, () -> {})).isTrue();
        assertThat(inbox.processOnce("psp-b", eventId, () -> {})).isTrue();
    }

    @Test
    void failedHandlerRollsBackSoTheRedeliveryIsProcessed() {
        assertThatThrownBy(() -> inbox.processOnce("psp", eventId, () -> {
            jdbc.update("INSERT INTO ledger_entries VALUES (?, 500)", eventId);
            throw new IllegalStateException("downstream timeout");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(ledgerRows()).isZero();

        assertThat(inbox.processOnce("psp", eventId,
                () -> jdbc.update("INSERT INTO ledger_entries VALUES (?, 500)", eventId))).isTrue();
        assertThat(ledgerRows()).isEqualTo(1);
    }

    @Test
    void markProcessedJoinsTheCallersTransaction() {
        tx.executeWithoutResult(s -> {
            assertThat(inbox.markProcessed("psp", eventId)).isTrue();
            s.setRollbackOnly();
        });
        assertThat(inbox.markProcessed("psp", eventId)).isTrue();
        assertThat(inbox.markProcessed("psp", eventId)).isFalse();
    }

    @Test
    void concurrentDuplicateDeliveriesRunTheHandlerOnce() throws Exception {
        int deliveries = 16;
        AtomicInteger runs = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(deliveries);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < deliveries; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return inbox.processOnce("psp", eventId, () -> {
                        runs.incrementAndGet();
                        jdbc.update("INSERT INTO ledger_entries VALUES (?, 500)", eventId);
                        sleep(50);
                    });
                }));
            }
            start.countDown();
            int processed = 0;
            for (Future<Boolean> r : results) {
                processed += r.get() ? 1 : 0;
            }
            assertThat(processed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(runs).hasValue(1);
        assertThat(ledgerRows()).isEqualTo(1);
    }

    private int ledgerRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE event_id = ?", Integer.class, eventId);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
