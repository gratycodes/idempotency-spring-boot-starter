package io.github.gratycodes.idempotency.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.support.ExternalServices;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The store contract against a real Redis (Testcontainers in CI). Expiry uses Redis TTLs, so time really passes. */
class RedisIdempotencyStoreContainerTest extends IdempotencyStoreContract {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private static RedisIdempotencyStore store;

    @BeforeAll
    static void start() {
        ExternalServices.Redis redis = ExternalServices.redis();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redis.host(), redis.port()));
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
        store = new RedisIdempotencyStore(template, "idem-test:");
    }

    @AfterAll
    static void stop() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Override
    IdempotencyStore store() {
        return store;
    }

    @Override
    void elapse(Duration d) throws InterruptedException {
        Thread.sleep(d.toMillis());
    }

    @Test
    void inFlightKeyCarriesTheLockTimeoutAsRedisTtl() {
        acquire("ttl");
        Long ttl = template.getExpire(store.redisKey(scope, "ttl"), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, LOCK.toMillis());
    }
}
