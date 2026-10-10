package io.github.gratycodes.samplepayments;

import io.github.gratycodes.idempotency.core.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes expired idempotency rows. The JDBC store does not expire rows on its own; Redis does. */
@Component
class IdempotencyHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyHousekeeping.class);
    private final IdempotencyStore store;

    IdempotencyHousekeeping(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    void purge() {
        int removed = store.purgeExpired();
        if (removed > 0) {
            log.info("Purged {} expired idempotency keys", removed);
        }
    }
}
