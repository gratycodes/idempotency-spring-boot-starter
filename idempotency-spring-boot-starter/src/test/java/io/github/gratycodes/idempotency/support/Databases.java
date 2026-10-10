package io.github.gratycodes.idempotency.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public final class Databases {

    private Databases() {}

    public static HikariDataSource h2() {
        return pool("jdbc:h2:mem:idem-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    public static HikariDataSource pool(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(24);
        HikariDataSource ds = new HikariDataSource(config);
        initSchema(ds);
        return ds;
    }

    public static void initSchema(DataSource ds) {
        new ResourceDatabasePopulator(new ClassPathResource("io/github/gratycodes/idempotency/jdbc/schema.sql"))
                .execute(ds);
    }
}
