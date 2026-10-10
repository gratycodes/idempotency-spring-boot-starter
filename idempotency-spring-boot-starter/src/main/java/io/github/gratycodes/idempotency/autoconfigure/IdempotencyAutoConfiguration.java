package io.github.gratycodes.idempotency.autoconfigure;

import io.github.gratycodes.idempotency.core.IdempotencyEngine;
import io.github.gratycodes.idempotency.core.IdempotencyMetrics;
import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.core.ResponseCachePolicy;
import io.github.gratycodes.idempotency.web.HeaderOrPrincipalScopeResolver;
import io.github.gratycodes.idempotency.web.IdempotencyFilter;
import io.github.gratycodes.idempotency.web.IdempotencyInterceptor;
import io.github.gratycodes.idempotency.web.IdempotencyScopeResolver;
import io.github.gratycodes.idempotency.web.IdempotencyWebSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration(afterName = {
        // Spring Boot 4
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        // Spring Boot 3
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"})
@ConditionalOnBooleanProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
@Import({StoreConfigurations.Jdbc.class, StoreConfigurations.Redis.class, StoreConfigurations.Memory.class,
        StoreConfigurations.WebhookInboxConfiguration.class})
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "idempotencyClock")
    Clock idempotencyClock() {
        return Clock.systemUTC();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MicrometerMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        IdempotencyMetrics idempotencyMetrics(ObjectProvider<MeterRegistry> registry) {
            MeterRegistry r = registry.getIfAvailable();
            return r == null ? IdempotencyMetrics.NOOP : new MicrometerIdempotencyMetrics(r);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotencyEngine idempotencyEngine(IdempotencyStore store, IdempotencyProperties props,
            ObjectProvider<IdempotencyMetrics> metrics) {
        return new IdempotencyEngine(store,
                new ResponseCachePolicy(props.isCacheServerErrors(), props.getNeverCacheStatuses()),
                props.getLockTimeout(), metrics.getIfAvailable(() -> IdempotencyMetrics.NOOP));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebMvcConfigurer.class)
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        IdempotencyScopeResolver idempotencyScopeResolver(IdempotencyProperties props) {
            return new HeaderOrPrincipalScopeResolver(props.getScope().getHeader(), props.getScope().isUsePrincipal());
        }

        @Bean
        IdempotencyWebSettings idempotencyWebSettings(IdempotencyProperties props) {
            return new IdempotencyWebSettings(props.getHeaderName(), props.isRequireKey(), props.getMaxKeyLength(),
                    props.getMethods(), props.getIncludePaths(), props.getTtl(), props.getRetryAfter(),
                    props.getReplayHeaders(), props.getFingerprintHeaders());
        }

        @Bean
        IdempotencyFilter idempotencyFilter(IdempotencyEngine engine, IdempotencyWebSettings settings,
                IdempotencyProperties props) {
            return new IdempotencyFilter(engine, settings, props.getFilterOrder());
        }

        @Bean
        IdempotencyInterceptor idempotencyInterceptor(IdempotencyEngine engine, IdempotencyWebSettings settings,
                IdempotencyScopeResolver scopeResolver) {
            return new IdempotencyInterceptor(engine, settings, scopeResolver);
        }

        @Bean
        WebMvcConfigurer idempotencyWebMvcConfigurer(IdempotencyInterceptor interceptor) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(interceptor);
                }
            };
        }
    }
}
