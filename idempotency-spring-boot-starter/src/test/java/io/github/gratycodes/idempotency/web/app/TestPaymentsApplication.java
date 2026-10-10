package io.github.gratycodes.idempotency.web.app;

import io.github.gratycodes.idempotency.Idempotent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A minimal API under test. Counts executions per idempotency key so tests can prove "exactly once". */
@SpringBootApplication
public class TestPaymentsApplication {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    Executions executions() {
        return new Executions();
    }

    public static class Executions {
        private final Map<String, AtomicInteger> byKey = new ConcurrentHashMap<>();

        int increment(String key) {
            return byKey.computeIfAbsent(key == null ? "<none>" : key, k -> new AtomicInteger()).incrementAndGet();
        }

        public int of(String key) {
            AtomicInteger n = byKey.get(key);
            return n == null ? 0 : n.get();
        }
    }

    public record PaymentRequest(long amount, String currency) {}

    public record Payment(String id, long amount, String currency, String status, Instant createdAt) {}

    @RestController
    static class PaymentsController {

        private final Executions executions;

        PaymentsController(Executions executions) {
            this.executions = executions;
        }

        @Idempotent
        @PostMapping("/payments")
        ResponseEntity<Payment> create(@RequestBody PaymentRequest request,
                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                @RequestParam(defaultValue = "0") long delayMs) throws InterruptedException {
            executions.increment(key);
            Thread.sleep(delayMs);
            Payment payment = new Payment("pay_" + UUID.randomUUID(), request.amount(), request.currency(), "succeeded",
                    Instant.now());
            return ResponseEntity.created(URI.create("/payments/" + payment.id()))
                    .header("X-Not-Replayed", UUID.randomUUID().toString())
                    .body(payment);
        }

        @Idempotent
        @PostMapping("/payments/declined")
        ResponseEntity<String> declined(@RequestHeader("Idempotency-Key") String key) {
            executions.increment(key);
            return ResponseEntity.status(402).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"card_declined\",\"attempt\":" + executions.of(key) + "}");
        }

        @Idempotent
        @PostMapping("/payments/unavailable")
        ResponseEntity<String> unavailable(@RequestHeader("Idempotency-Key") String key) {
            executions.increment(key);
            return ResponseEntity.status(503).body("try again");
        }

        @Idempotent
        @PostMapping("/payments/boom")
        String boom(@RequestHeader("Idempotency-Key") String key) {
            executions.increment(key);
            throw new IllegalStateException("simulated crash in the handler");
        }

        @Idempotent(key = Idempotent.KeyRequirement.REQUIRED)
        @PostMapping("/refunds")
        ResponseEntity<String> refund(@RequestHeader(value = "Idempotency-Key", required = false) String key) {
            executions.increment(key);
            return ResponseEntity.status(201).body("refunded");
        }

        @Idempotent(ttl = "300ms")
        @PostMapping("/short-lived")
        String shortLived(@RequestHeader("Idempotency-Key") String key) {
            return "run " + executions.increment(key);
        }

        @PostMapping("/not-idempotent")
        String notIdempotent(@RequestHeader("Idempotency-Key") String key) {
            return "run " + executions.increment(key);
        }

        @PostMapping("/api/included/orders")
        String includedByPath(@RequestHeader("Idempotency-Key") String key) {
            return "run " + executions.increment(key);
        }

        @PostMapping(path = "/forms", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        @Idempotent
        String form(@RequestHeader("Idempotency-Key") String key, @RequestParam String amount) {
            return "amount " + amount + " run " + executions.increment(key);
        }
    }
}
