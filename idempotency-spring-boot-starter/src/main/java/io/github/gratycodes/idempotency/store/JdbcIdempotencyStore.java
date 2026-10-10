package io.github.gratycodes.idempotency.store;

import io.github.gratycodes.idempotency.core.AcquireResult;
import io.github.gratycodes.idempotency.core.HeaderCodec;
import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.core.StoredResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Relational store, tested on PostgreSQL and on H2 in PostgreSQL mode. Each operation is a single
 * statement in auto-commit, so no transaction is held open while the handler runs.
 *
 * <p>Claiming a key is two atomic steps: {@code INSERT ... ON CONFLICT DO NOTHING} for a new key,
 * then a conditional {@code UPDATE} that takes over the row only if its lock or TTL has expired.
 * PostgreSQL re-checks the {@code WHERE} clause after waiting on the row lock, so two requests
 * racing to take over a stale lock cannot both win.
 *
 * <p>Times are stored as epoch milliseconds from the application clock, not the database clock.
 * Keep application servers' clocks in sync (NTP); a skew larger than the lock timeout can let a
 * second request take over a live lock.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String insertSql;
    private final String takeOverSql;
    private final String selectSql;
    private final String completeSql;
    private final String releaseSql;
    private final String purgeSql;

    public JdbcIdempotencyStore(DataSource dataSource, String table, Clock clock) {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
        this.insertSql = "INSERT INTO " + table
                + " (scope, idem_key, fingerprint, state, lock_token, locked_until, expires_at, created_at)"
                + " VALUES (?, ?, ?, 'IN_FLIGHT', ?, ?, ?, ?) ON CONFLICT DO NOTHING";
        this.takeOverSql = "UPDATE " + table + " SET fingerprint = ?, state = 'IN_FLIGHT', lock_token = ?,"
                + " locked_until = ?, expires_at = ?, created_at = ?, status_code = NULL,"
                + " response_headers = NULL, response_body = NULL"
                + " WHERE scope = ? AND idem_key = ?"
                + " AND ((state = 'IN_FLIGHT' AND locked_until <= ?) OR (state = 'COMPLETED' AND expires_at <= ?))";
        this.selectSql = "SELECT fingerprint, state, status_code, response_headers, response_body FROM " + table
                + " WHERE scope = ? AND idem_key = ?";
        this.completeSql = "UPDATE " + table + " SET state = 'COMPLETED', lock_token = NULL, status_code = ?,"
                + " response_headers = ?, response_body = ?, expires_at = ?"
                + " WHERE scope = ? AND idem_key = ? AND state = 'IN_FLIGHT' AND lock_token = ?";
        this.releaseSql = "DELETE FROM " + table
                + " WHERE scope = ? AND idem_key = ? AND state = 'IN_FLIGHT' AND lock_token = ?";
        this.purgeSql = "DELETE FROM " + table + " WHERE expires_at <= ?";
    }

    @Override
    public AcquireResult tryAcquire(String scope, String key, String fingerprint, Duration lockTimeout) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long now = clock.millis();
            long lockedUntil = now + lockTimeout.toMillis();
            String token = UUID.randomUUID().toString();
            // For an in-flight row, expires_at = locked_until so purgeExpired also clears dead locks.
            if (jdbc.update(insertSql, scope, key, fingerprint, token, lockedUntil, lockedUntil, now) == 1) {
                return new AcquireResult.Acquired(token);
            }
            if (jdbc.update(takeOverSql, fingerprint, token, lockedUntil, lockedUntil, now, scope, key, now, now) == 1) {
                return new AcquireResult.Acquired(token);
            }
            List<AcquireResult> existing = jdbc.query(selectSql, (rs, i) -> {
                String fp = rs.getString("fingerprint");
                if ("COMPLETED".equals(rs.getString("state"))) {
                    return new AcquireResult.Completed(fp, new StoredResponse(rs.getInt("status_code"),
                            HeaderCodec.decode(rs.getString("response_headers")), rs.getBytes("response_body")));
                }
                return new AcquireResult.InFlight(fp);
            }, scope, key);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            // The row was released or purged between our statements; try again.
        }
        throw new IllegalStateException("Could not claim idempotency key after " + MAX_ATTEMPTS + " attempts");
    }

    @Override
    public boolean complete(String scope, String key, String token, StoredResponse response, Duration ttl) {
        return jdbc.update(completeSql, response.status(), HeaderCodec.encode(response.headers()), response.body(),
                clock.millis() + ttl.toMillis(), scope, key, token) == 1;
    }

    @Override
    public boolean release(String scope, String key, String token) {
        return jdbc.update(releaseSql, scope, key, token) == 1;
    }

    @Override
    public int purgeExpired() {
        return jdbc.update(purgeSql, clock.millis());
    }
}
