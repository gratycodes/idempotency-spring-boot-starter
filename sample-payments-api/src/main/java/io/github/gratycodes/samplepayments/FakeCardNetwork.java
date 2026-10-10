package io.github.gratycodes.samplepayments;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Stands in for the card network: the side effect we must not repeat. Counts charges so the demo
 * (and the tests) can show that a retried request did not charge twice.
 */
@Component
public class FakeCardNetwork {

    private final AtomicInteger charges = new AtomicInteger();

    public String charge(String customer, long amountMinor, String currency) {
        try {
            Thread.sleep(300); // network latency, long enough for a client to time out and retry
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "ch_" + charges.incrementAndGet();
    }

    public int charges() {
        return charges.get();
    }
}
