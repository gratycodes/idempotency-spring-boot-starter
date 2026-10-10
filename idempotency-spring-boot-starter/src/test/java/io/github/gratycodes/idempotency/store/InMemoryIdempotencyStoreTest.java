package io.github.gratycodes.idempotency.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.core.StoredResponse;
import io.github.gratycodes.idempotency.support.MutableClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest extends IdempotencyStoreContract {

    private final MutableClock clock = new MutableClock();
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);

    @Override
    IdempotencyStore store() {
        return store;
    }

    @Override
    void elapse(Duration d) {
        clock.advance(d);
    }

    @Test
    void purgeRemovesExpiredEntriesOnly() {
        String done = acquire("done");
        store.complete(scope, "done", done, new StoredResponse(200, List.of(), new byte[0]), Duration.ofHours(1));
        acquire("stale");
        clock.advance(LOCK.plusMillis(1));
        assertThat(store.purgeExpired()).isEqualTo(1);
    }
}
