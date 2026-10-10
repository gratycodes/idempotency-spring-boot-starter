package io.github.gratycodes.idempotency.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gratycodes.idempotency.core.IdempotencyEngine;
import io.github.gratycodes.idempotency.core.IdempotencyMetrics;
import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.store.InMemoryIdempotencyStore;
import io.github.gratycodes.idempotency.store.JdbcIdempotencyStore;
import io.github.gratycodes.idempotency.web.IdempotencyFilter;
import io.github.gratycodes.idempotency.web.IdempotencyInterceptor;
import io.github.gratycodes.idempotency.webhook.WebhookInbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class IdempotencyAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class));

    @Test
    void withoutABackendItFallsBackToMemoryAndWiresTheWebLayer() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(IdempotencyEngine.class);
            assertThat(ctx.getBean(IdempotencyStore.class)).isInstanceOf(InMemoryIdempotencyStore.class);
            assertThat(ctx).hasSingleBean(IdempotencyFilter.class);
            assertThat(ctx).hasSingleBean(IdempotencyInterceptor.class);
            assertThat(ctx).doesNotHaveBean(WebhookInbox.class);
        });
    }

    @Test
    void aDataSourceSelectsTheJdbcStoreAndTheWebhookInbox() {
        runner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:autoconfig;MODE=PostgreSQL",
                        "idempotency.jdbc.initialize-schema=true")
                .run(ctx -> {
                    assertThat(ctx.getBean(IdempotencyStore.class)).isInstanceOf(JdbcIdempotencyStore.class);
                    assertThat(ctx).hasSingleBean(WebhookInbox.class);
                });
    }

    @Test
    void storeMemoryOverridesAutoDetection() {
        runner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:autoconfig2", "idempotency.store=memory")
                .run(ctx -> assertThat(ctx.getBean(IdempotencyStore.class)).isInstanceOf(InMemoryIdempotencyStore.class));
    }

    @Test
    void storeRedisWithoutRedisFailsAtStartupInsteadOfSilentlyUsingMemory() {
        runner.withPropertyValues("idempotency.store=redis").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void anApplicationStoreBeanWins() {
        InMemoryIdempotencyStore mine = new InMemoryIdempotencyStore();
        runner.withBean(IdempotencyStore.class, () -> mine)
                .run(ctx -> assertThat(ctx.getBean(IdempotencyStore.class)).isSameAs(mine));
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("idempotency.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(IdempotencyEngine.class);
            assertThat(ctx).doesNotHaveBean(IdempotencyFilter.class);
        });
    }

    @Test
    void metricsUseMicrometerWhenARegistryExists() {
        runner.withBean(SimpleMeterRegistry.class).run(ctx ->
                assertThat(ctx.getBean(IdempotencyMetrics.class)).isInstanceOf(MicrometerIdempotencyMetrics.class));
        runner.run(ctx -> assertThat(ctx.getBean(IdempotencyMetrics.class)).isSameAs(IdempotencyMetrics.NOOP));
    }

    @Test
    void outsideAWebApplicationOnlyTheCoreIsConfigured() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(IdempotencyEngine.class);
                    assertThat(ctx).doesNotHaveBean(IdempotencyFilter.class);
                });
    }
}
