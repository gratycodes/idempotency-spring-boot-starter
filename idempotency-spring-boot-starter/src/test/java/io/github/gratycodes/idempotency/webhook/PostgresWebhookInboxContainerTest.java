package io.github.gratycodes.idempotency.webhook;

import com.zaxxer.hikari.HikariDataSource;
import io.github.gratycodes.idempotency.support.Databases;
import io.github.gratycodes.idempotency.support.ExternalServices;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

class PostgresWebhookInboxContainerTest extends WebhookInboxContract {

    private static HikariDataSource ds;

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
    DataSource dataSource() {
        return ds;
    }
}
