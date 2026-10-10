package io.github.gratycodes.idempotency.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gratycodes.idempotency.core.AcquireResult;
import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.core.StoredResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behaviour every store must have. Each backend runs this whole suite. */
abstract class IdempotencyStoreContract {

    static final Duration LOCK = Duration.ofMillis(400);
    static final Duration TTL = Duration.ofMillis(400);
    static final String FP = "a".repeat(64);
    static final String OTHER_FP = "b".repeat(64);
    static final int RACERS = 48;

    protected String scope;

    abstract IdempotencyStore store();

    /** Moves time forward past LOCK and TTL: a fake clock for most stores, real sleep for Redis. */
    abstract void elapse(Duration d) throws InterruptedException;

    @BeforeEach
    void freshScope() {
        scope = "tenant-" + UUID.randomUUID();
    }

    @Test
    void firstClaimWins() {
        assertThat(store().tryAcquire(scope, "k1", FP, LOCK)).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void duplicateWhileInFlightReportsTheOriginalFingerprint() {
        store().tryAcquire(scope, "k1", FP, LOCK);
        assertThat(store().tryAcquire(scope, "k1", OTHER_FP, LOCK)).isEqualTo(new AcquireResult.InFlight(FP));
    }

    @Test
    void completedResponseComesBackByteForByte() {
        String token = acquire("k1");
        byte[] body = new byte[256];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        StoredResponse response = new StoredResponse(201, List.of(
                new StoredResponse.Header("Content-Type", "application/json;charset=UTF-8"),
                new StoredResponse.Header("Location", "/payments/pay_1"),
                new StoredResponse.Header("Location", "/payments/pay_1?v=2")), body);

        assertThat(store().complete(scope, "k1", token, response, TTL)).isTrue();

        assertThat(store().tryAcquire(scope, "k1", FP, LOCK)).isEqualTo(new AcquireResult.Completed(FP, response));
    }

    @Test
    void emptyBodyAndNoHeadersRoundTrip() {
        String token = acquire("k1");
        StoredResponse response = new StoredResponse(204, List.of(), new byte[0]);
        store().complete(scope, "k1", token, response, TTL);
        assertThat(store().tryAcquire(scope, "k1", FP, LOCK)).isEqualTo(new AcquireResult.Completed(FP, response));
    }

    @Test
    void onlyTheOwnerCanCompleteOrRelease() {
        acquire("k1");
        StoredResponse response = new StoredResponse(200, List.of(), new byte[] {1});
        assertThat(store().complete(scope, "k1", "not-the-token", response, TTL)).isFalse();
        assertThat(store().release(scope, "k1", "not-the-token")).isFalse();
        assertThat(store().tryAcquire(scope, "k1", FP, LOCK)).isInstanceOf(AcquireResult.InFlight.class);
    }

    @Test
    void releaseFreesTheKey() {
        String token = acquire("k1");
        assertThat(store().release(scope, "k1", token)).isTrue();
        assertThat(store().tryAcquire(scope, "k1", OTHER_FP, LOCK)).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void completedKeyCannotBeReleased() {
        String token = acquire("k1");
        store().complete(scope, "k1", token, new StoredResponse(200, List.of(), new byte[0]), TTL);
        assertThat(store().release(scope, "k1", token)).isFalse();
        assertThat(store().tryAcquire(scope, "k1", FP, LOCK)).isInstanceOf(AcquireResult.Completed.class);
    }

    @Test
    void staleLockIsTakenOverAndTheOldOwnerCanNoLongerWrite() throws Exception {
        String crashed = acquire("k1");
        elapse(LOCK.plusMillis(300));

        AcquireResult takeover = store().tryAcquire(scope, "k1", OTHER_FP, LOCK);
        assertThat(takeover).isInstanceOf(AcquireResult.Acquired.class);

        StoredResponse late = new StoredResponse(200, List.of(), "late".getBytes());
        assertThat(store().complete(scope, "k1", crashed, late, TTL)).isFalse();
        assertThat(store().release(scope, "k1", crashed)).isFalse();
        assertThat(store().tryAcquire(scope, "k1", OTHER_FP, LOCK)).isEqualTo(new AcquireResult.InFlight(OTHER_FP));
    }

    @Test
    void completedKeyExpiresAfterTtl() throws Exception {
        String token = acquire("k1");
        store().complete(scope, "k1", token, new StoredResponse(200, List.of(), new byte[0]), TTL);
        elapse(TTL.plusMillis(300));
        assertThat(store().tryAcquire(scope, "k1", OTHER_FP, LOCK)).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void sameKeyInAnotherScopeIsIndependent() {
        acquire("k1");
        assertThat(store().tryAcquire(scope + "-other", "k1", OTHER_FP, LOCK)).isInstanceOf(AcquireResult.Acquired.class);
        assertThat(store().tryAcquire("x:" + scope, "k1", OTHER_FP, LOCK)).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void exactlyOneOfManyConcurrentClaimsWins() throws Exception {
        List<AcquireResult> results = race(() -> store().tryAcquire(scope, "hot", FP, Duration.ofSeconds(30)));
        assertThat(results).filteredOn(r -> r instanceof AcquireResult.Acquired).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof AcquireResult.InFlight).hasSize(RACERS - 1);
    }

    @Test
    void exactlyOneOfManyConcurrentTakeoversOfAStaleLockWins() throws Exception {
        acquire("hot");
        elapse(LOCK.plusMillis(300));
        List<AcquireResult> results = race(() -> store().tryAcquire(scope, "hot", FP, Duration.ofSeconds(30)));
        assertThat(results).filteredOn(r -> r instanceof AcquireResult.Acquired).hasSize(1);
    }

    protected String acquire(String key) {
        AcquireResult r = store().tryAcquire(scope, key, FP, LOCK);
        assertThat(r).isInstanceOf(AcquireResult.Acquired.class);
        return ((AcquireResult.Acquired) r).token();
    }

    private static <T> List<T> race(Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
