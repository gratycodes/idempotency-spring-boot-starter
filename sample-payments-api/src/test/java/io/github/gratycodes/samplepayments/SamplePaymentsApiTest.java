package io.github.gratycodes.samplepayments;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SamplePaymentsApiTest {

    @Autowired
    Environment env;

    @Autowired
    FakeCardNetwork cardNetwork;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aClientThatRetriesAfterATimeoutIsChargedOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"amount\":4999,\"currency\":\"EUR\",\"customer\":\"cus_synthetic_001\"}";
        int chargesBefore = cardNetwork.charges();

        // The client fires, gives up waiting, and retries while the first attempt is still running,
        // then retries once more after it finished.
        CompletableFuture<HttpResponse<String>> first = http.sendAsync(pay(key, body), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(40);
        CompletableFuture<HttpResponse<String>> overlapping = http.sendAsync(pay(key, body), HttpResponse.BodyHandlers.ofString());
        List<HttpResponse<String>> both = List.of(first.get(), overlapping.get());
        HttpResponse<String> original = both.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow();
        HttpResponse<String> later = http.send(pay(key, body), HttpResponse.BodyHandlers.ofString());

        assertThat(both).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        assertThat(later.statusCode()).isEqualTo(201);
        assertThat(later.body()).isEqualTo(original.body());
        assertThat(later.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(cardNetwork.charges() - chargesBefore).isEqualTo(1);
    }

    @Test
    void paymentsWithoutAKeyAreRefused() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(url("/v1/payments"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":100,\"currency\":\"EUR\"}"))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    @Test
    void aDuplicateSettlementWebhookWritesOneLedgerEntry() throws Exception {
        HttpResponse<String> created = http.send(pay(UUID.randomUUID().toString(),
                "{\"amount\":1500,\"currency\":\"GBP\",\"customer\":\"cus_synthetic_002\"}"), HttpResponse.BodyHandlers.ofString());
        String paymentId = created.headers().firstValue("Location").orElseThrow().replace("/v1/payments/", "");
        String eventId = "evt_" + UUID.randomUUID();
        String event = "{\"id\":\"" + eventId + "\",\"type\":\"payment.settled\",\"paymentId\":\"" + paymentId
                + "\",\"amount\":1500}";

        List<String> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            HttpRequest delivery = HttpRequest.newBuilder(url("/webhooks/psp"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(event))
                    .build();
            results.add(http.send(delivery, HttpResponse.BodyHandlers.ofString()).body());
        }

        assertThat(results.get(0)).contains("processed");
        assertThat(results.subList(1, 3)).allSatisfy(r -> assertThat(r).contains("duplicate"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", Integer.class, paymentId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("settled");
    }

    @Test
    void outcomesShowUpInActuatorMetrics() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"amount\":700,\"currency\":\"AED\",\"customer\":\"cus_synthetic_003\"}";
        http.send(pay(key, body), HttpResponse.BodyHandlers.ofString());
        http.send(pay(key, body), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> metric = http.send(HttpRequest.newBuilder(
                url("/actuator/metrics/idempotency.requests?tag=outcome:replayed")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(metric.statusCode()).isEqualTo(200);
        assertThat(metric.body()).contains("\"COUNT\"");
    }

    private HttpRequest pay(String key, String json) {
        return HttpRequest.newBuilder(url("/v1/payments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header("X-Api-Client", "merchant_synthetic_42")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    }
}
