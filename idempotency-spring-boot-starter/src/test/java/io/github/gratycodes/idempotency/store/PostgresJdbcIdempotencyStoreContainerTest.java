package io.github.gratycodes.idempotency.store;

import com.zaxxer.hikari.HikariDataSource;
import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.support.Databases;
import io.github.gratycodes.idempotency.support.ExternalServices;
import io.github.gratycodes.idempotency.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** The store contract against a real PostgreSQL (Testcontainers in CI). */
class PostgresJdbcIdempotencyStoreContainerTest extends IdempotencyStoreContract {

    private static HikariDataSource ds;
    private final MutableClock clock = new MutableClock();
    private JdbcIdempotencyStore store;

    @BeforeAll
    static void start() {
        ExternalServices.Postgres pg = ExternalServices.postgres();
        ds = Databases.pool(pg.url(), pg.user(), pg.password());
    }

    @AfterAll
    static void stop() {
        if (ds != null) {
            ds.close();
        }
    }

    @Override
    IdempotencyStore store() {
        if (store == null) {
            store = new JdbcIdempotencyStore(ds, "idempotency_keys", clock);
        }
        return store;
    }

    @Override
    void elapse(Duration d) {
        clock.advance(d);
    }
}
