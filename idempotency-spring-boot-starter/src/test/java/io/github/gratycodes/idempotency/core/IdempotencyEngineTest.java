package io.github.gratycodes.idempotency.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gratycodes.idempotency.core.IdempotencyEngine.Decision;
import io.github.gratycodes.idempotency.core.IdempotencyMetrics.Outcome;
import io.github.gratycodes.idempotency.store.InMemoryIdempotencyStore;
import io.github.gratycodes.idempotency.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdempotencyEngineTest {

    private final MutableClock clock = new MutableClock();
    private final List<Outcome> outcomes = new ArrayList<>();
    private final IdempotencyEngine engine = new IdempotencyEngine(new InMemoryIdempotencyStore(clock),
            ResponseCachePolicy.defaults(), Duration.ofSeconds(30), outcomes::add);
    private final Duration ttl = Duration.ofHours(24);

    private static final StoredResponse CREATED = new StoredResponse(201, List.of(), "{\"id\":\"pay_1\"}".getBytes());

    @Test
    void firstRetryMismatchAndConcurrentDuplicate() {
        Decision first = engine.begin("t1", "k", "fp1");
        assertThat(first).isInstanceOf(Decision.Proceed.class);

        assertThat(engine.begin("t1", "k", "fp1")).isInstanceOf(Decision.InProgress.class);
        assertThat(engine.begin("t1", "k", "fp2")).isInstanceOf(Decision.Mismatch.class);

        engine.finish("t1", "k", ((Decision.Proceed) first).token(), CREATED, ttl);

        assertThat(engine.begin("t1", "k", "fp1")).isEqualTo(new Decision.Replay(CREATED));
        assertThat(engine.begin("t1", "k", "fp2")).isInstanceOf(Decision.Mismatch.class);
        assertThat(outcomes).containsExactly(Outcome.EXECUTED, Outcome.CONFLICT, Outcome.MISMATCH,
                Outcome.REPLAYED, Outcome.MISMATCH);
    }

    @Test
    void serverErrorsAreNotCachedSoTheClientCanRetry() {
        Decision.Proceed first = (Decision.Proceed) engine.begin("t1", "k", "fp1");
        engine.finish("t1", "k", first.token(), new StoredResponse(503, List.of(), new byte[0]), ttl);
        assertThat(engine.begin("t1", "k", "fp1")).isInstanceOf(Decision.Proceed.class);
        assertThat(outcomes).containsExactly(Outcome.EXECUTED, Outcome.RELEASED, Outcome.EXECUTED);
    }

    @Test
    void abortReleasesTheKey() {
        Decision.Proceed first = (Decision.Proceed) engine.begin("t1", "k", "fp1");
        engine.abort("t1", "k", first.token());
        assertThat(engine.begin("t1", "k", "fp1")).isInstanceOf(Decision.Proceed.class);
    }

    @Test
    void finishingAfterTheLockWasTakenOverIsReportedAndDoesNotOverwrite() {
        Decision.Proceed slow = (Decision.Proceed) engine.begin("t1", "k", "fp1");
        clock.advance(Duration.ofSeconds(31));
        Decision.Proceed second = (Decision.Proceed) engine.begin("t1", "k", "fp1");

        engine.finish("t1", "k", slow.token(), new StoredResponse(201, List.of(), "slow".getBytes()), ttl);
        engine.finish("t1", "k", second.token(), CREATED, ttl);

        assertThat(engine.begin("t1", "k", "fp1")).isEqualTo(new Decision.Replay(CREATED));
        assertThat(outcomes).contains(Outcome.LOCK_LOST);
    }

    @Test
    void cachePolicyDefaults() {
        ResponseCachePolicy policy = ResponseCachePolicy.defaults();
        assertThat(policy.shouldCache(200)).isTrue();
        assertThat(policy.shouldCache(201)).isTrue();
        assertThat(policy.shouldCache(400)).isTrue();
        assertThat(policy.shouldCache(402)).isTrue();
        assertThat(policy.shouldCache(408)).isFalse();
        assertThat(policy.shouldCache(429)).isFalse();
        assertThat(policy.shouldCache(500)).isFalse();
        assertThat(policy.shouldCache(103)).isFalse();
        assertThat(new ResponseCachePolicy(true, Set.of()).shouldCache(500)).isTrue();
    }
}
