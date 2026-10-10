package io.github.gratycodes.samplepayments;

import io.github.gratycodes.idempotency.Idempotent;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
class PaymentsController {

    private static final Set<String> CURRENCIES = Set.of("EUR", "USD", "GBP", "AED", "INR");

    record CreatePayment(long amount, String currency, String customer) {}

    record Payment(String id, long amount, String currency, String customer, String status, String chargeId,
            Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final FakeCardNetwork cardNetwork;

    PaymentsController(JdbcTemplate jdbc, FakeCardNetwork cardNetwork) {
        this.jdbc = jdbc;
        this.cardNetwork = cardNetwork;
    }

    /** Charges a customer. Retries with the same Idempotency-Key never charge twice. */
    @Idempotent(key = Idempotent.KeyRequirement.REQUIRED)
    @PostMapping
    ResponseEntity<?> create(@RequestBody CreatePayment request) {
        if (request.amount() <= 0 || request.currency() == null || !CURRENCIES.contains(request.currency())) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request",
                    "message", "amount must be positive and currency one of " + CURRENCIES));
        }
        String chargeId = cardNetwork.charge(request.customer(), request.amount(), request.currency());
        Payment payment = new Payment("pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                request.amount(), request.currency(), request.customer(), "authorized", chargeId, Instant.now());
        jdbc.update("INSERT INTO payments (id, amount, currency, customer, status, charge_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", payment.id(), payment.amount(), payment.currency(),
                payment.customer(), payment.status(), payment.chargeId(), payment.createdAt().toEpochMilli());
        return ResponseEntity.created(URI.create("/v1/payments/" + payment.id())).body(payment);
    }

    @GetMapping("/{id}")
    ResponseEntity<Payment> get(@PathVariable String id) {
        List<Payment> found = jdbc.query("SELECT * FROM payments WHERE id = ?", (rs, i) -> new Payment(
                rs.getString("id"), rs.getLong("amount"), rs.getString("currency"), rs.getString("customer"),
                rs.getString("status"), rs.getString("charge_id"), Instant.ofEpochMilli(rs.getLong("created_at"))), id);
        return found.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(found.get(0));
    }
}
