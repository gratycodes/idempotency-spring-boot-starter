package io.github.gratycodes.idempotency.webhook;

import com.zaxxer.hikari.HikariDataSource;
import io.github.gratycodes.idempotency.support.Databases;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;

class H2WebhookInboxTest extends WebhookInboxContract {

    private static final HikariDataSource DS = Databases.h2();

    @AfterAll
    static void close() {
        DS.close();
    }

    @Override
    DataSource dataSource() {
        return DS;
    }
}
