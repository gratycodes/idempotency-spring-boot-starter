package io.github.gratycodes.idempotency.autoconfigure;

import io.github.gratycodes.idempotency.core.IdempotencyMetrics;
import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.store.InMemoryIdempotencyStore;
import io.github.gratycodes.idempotency.store.JdbcIdempotencyStore;
import io.github.gratycodes.idempotency.store.RedisIdempotencyStore;
import io.github.gratycodes.idempotency.webhook.JdbcWebhookInbox;
import io.github.gratycodes.idempotency.webhook.WebhookInbox;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Store selection. Imported in order, so under {@code store=auto} JDBC wins over Redis over memory. */
final class StoreConfigurations {

    static final String SCHEMA = "io/github/gratycodes/idempotency/jdbc/schema.sql";

    private StoreConfigurations() {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(IdempotencyStore.class)
    @Conditional(Jdbc.Selected.class)
    static class Jdbc {

        @Bean
        IdempotencyStore idempotencyStore(DataSource dataSource, IdempotencyProperties props, Clock idempotencyClock) {
            initializeSchema(dataSource, props);
            return new JdbcIdempotencyStore(dataSource, props.getJdbc().getTable(), idempotencyClock);
        }

        static class Selected extends StoreSelected {
            Selected() {
                super(IdempotencyProperties.Store.JDBC);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({StringRedisTemplate.class, RedisConnectionFactory.class})
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(IdempotencyStore.class)
    @Conditional(Redis.Selected.class)
    static class Redis {

        @Bean
        IdempotencyStore idempotencyStore(RedisConnectionFactory connectionFactory, IdempotencyProperties props) {
            return new RedisIdempotencyStore(new StringRedisTemplate(connectionFactory), props.getRedis().getKeyPrefix());
        }

        static class Selected extends StoreSelected {
            Selected() {
                super(IdempotencyProperties.Store.REDIS);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(IdempotencyStore.class)
    @Conditional(Memory.Selected.class)
    static class Memory {

        private static final Logger log = LoggerFactory.getLogger(Memory.class);

        @Bean
        IdempotencyStore idempotencyStore(Clock idempotencyClock) {
            log.warn("Using the in-memory idempotency store. Keys are not shared between instances and are lost "
                    + "on restart; configure a DataSource or Redis for production.");
            return new InMemoryIdempotencyStore(idempotencyClock);
        }

        static class Selected extends StoreSelected {
            Selected() {
                super(IdempotencyProperties.Store.MEMORY);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({JdbcTemplate.class, TransactionTemplate.class})
    @ConditionalOnBean(DataSource.class)
    static class WebhookInboxConfiguration {

        @Bean
        @ConditionalOnMissingBean
        WebhookInbox webhookInbox(DataSource dataSource, ObjectProvider<PlatformTransactionManager> txManager,
                IdempotencyProperties props, Clock idempotencyClock, ObjectProvider<IdempotencyMetrics> metrics) {
            initializeSchema(dataSource, props);
            PlatformTransactionManager tm = txManager.getIfUnique(() -> new DataSourceTransactionManager(dataSource));
            return new JdbcWebhookInbox(dataSource, new TransactionTemplate(tm), props.getWebhook().getTable(),
                    idempotencyClock, metrics.getIfAvailable(() -> IdempotencyMetrics.NOOP));
        }
    }

    static void initializeSchema(DataSource dataSource, IdempotencyProperties props) {
        if (props.getJdbc().isInitializeSchema()) {
            new ResourceDatabasePopulator(new ClassPathResource(SCHEMA)).execute(dataSource);
        }
    }

    abstract static class StoreSelected implements Condition {
        private final IdempotencyProperties.Store store;

        StoreSelected(IdempotencyProperties.Store store) {
            this.store = store;
        }

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment().getProperty("idempotency.store", "auto");
            return value.equalsIgnoreCase("auto") || value.equalsIgnoreCase(store.name());
        }
    }
}
