package io.github.gratycodes.idempotency.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.core.StoredResponse;
import io.github.gratycodes.idempotency.support.Databases;
import io.github.gratycodes.idempotency.support.MutableClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class H2JdbcIdempotencyStoreTest extends IdempotencyStoreContract {

    private static final HikariDataSource DS = Databases.h2();
    private final MutableClock clock = new MutableClock();
    private final JdbcIdempotencyStore store = new JdbcIdempotencyStore(DS, "idempotency_keys", clock);

    @AfterAll
    static void close() {
        DS.close();
    }

    @Override
    IdempotencyStore store() {
        return store;
    }

    @Override
    void elapse(Duration d) {
        clock.advance(d);
    }

    @Test
    void purgeDeletesExpiredRows() {
        String done = acquire("done");
        store.complete(scope, "done", done, new StoredResponse(200, List.of(), new byte[0]), Duration.ofDays(1));
        acquire("stale");
        clock.advance(LOCK.plusMillis(1));
        assertThat(store.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(store.tryAcquire(scope, "done", FP, LOCK))
                .isInstanceOf(io.github.gratycodes.idempotency.core.AcquireResult.Completed.class);
    }

    @Test
    void rejectsTableNamesThatCouldInjectSql() {
        assertThatThrownBy(() -> new JdbcIdempotencyStore(DS, "keys; DROP TABLE x", clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
