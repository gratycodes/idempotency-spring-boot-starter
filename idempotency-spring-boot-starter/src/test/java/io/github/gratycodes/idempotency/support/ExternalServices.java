package io.github.gratycodes.idempotency.support;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL and Redis for integration tests. Testcontainers by default (this is what CI runs);
 * set IT_POSTGRES_URL / IT_POSTGRES_USER or IT_REDIS_HOST / IT_REDIS_PORT to use servers you already
 * have. Without either, the tests are skipped, and CI fails if they were skipped.
 */
public final class ExternalServices {

    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> redis;

    private ExternalServices() {}

    public record Postgres(String url, String user, String password) {}

    public record Redis(String host, int port) {}

    public static synchronized Postgres postgres() {
        String url = System.getenv("IT_POSTGRES_URL");
        if (url != null) {
            return new Postgres(url, env("IT_POSTGRES_USER", "postgres"), env("IT_POSTGRES_PASSWORD", ""));
        }
        assumeTrue(dockerAvailable(), "Docker is not available and IT_POSTGRES_URL is not set");
        if (postgres == null) {
            postgres = new PostgreSQLContainer("postgres:17-alpine");
            postgres.start();
        }
        return new Postgres(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    public static synchronized Redis redis() {
        String host = System.getenv("IT_REDIS_HOST");
        if (host != null) {
            return new Redis(host, Integer.parseInt(env("IT_REDIS_PORT", "6379")));
        }
        assumeTrue(dockerAvailable(), "Docker is not available and IT_REDIS_HOST is not set");
        if (redis == null) {
            redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            redis.start();
        }
        return new Redis(redis.getHost(), redis.getMappedPort(6379));
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : v;
    }
}
