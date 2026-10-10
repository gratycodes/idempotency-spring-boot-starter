package io.github.gratycodes.idempotency.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gratycodes.idempotency.web.app.TestPaymentsApplication;
import io.github.gratycodes.idempotency.web.app.TestPaymentsApplication.Executions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * The HTTP semantics, end to end through a real Tomcat. Each store backend runs this whole suite.
 */
@SpringBootTest(classes = TestPaymentsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "idempotency.scope.header=X-Tenant-Id",
                "idempotency.include-paths=/api/included/**",
                "idempotency.lock-timeout=10s"})
abstract class IdempotentHttpApiContract {

    static final String PAYMENT = "{\"amount\":1000,\"currency\":\"EUR\"}";

    @Autowired
    Environment env;

    @Autowired
    Executions executions;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String key;

    @BeforeEach
    void newKey() {
        key = "key-" + UUID.randomUUID();
    }

    @Test
    void retryReplaysTheFirstResponseByteForByte() throws Exception {
        HttpResponse<byte[]> first = post("/payments", key, "tenant-a", PAYMENT);
        HttpResponse<byte[]> retry = post("/payments", key, "tenant-a", PAYMENT);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
        assertThat(retry.headers().firstValue("Content-Type")).isEqualTo(first.headers().firstValue("Content-Type"));
        // Headers not listed in idempotency.replay-headers are not stored.
        assertThat(retry.headers().firstValue("X-Not-Replayed")).isEmpty();
        assertThat(executions.of(key)).isEqualTo(1);
    }

    @Test
    void retryWithReorderedJsonIsTheSameRequest() throws Exception {
        post("/payments", key, "tenant-a", PAYMENT);
        HttpResponse<byte[]> retry = post("/payments", key, "tenant-a", "{ \"currency\": \"EUR\", \"amount\": 1000 }");
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(executions.of(key)).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIs422() throws Exception {
        post("/payments", key, "tenant-a", PAYMENT);
        HttpResponse<byte[]> reuse = post("/payments", key, "tenant-a", "{\"amount\":9999,\"currency\":\"EUR\"}");

        assertThat(reuse.statusCode()).isEqualTo(422);
        assertThat(reuse.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/problem+json"));
        assertThat(text(reuse)).contains("already used with a different request");
        assertThat(executions.of(key)).isEqualTo(1);
    }

    @Test
    void parallelDuplicatesExecuteExactlyOnce() throws Exception {
        int clients = 24;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<HttpResponse<byte[]>> responses = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return post("/payments?delayMs=400", key, "tenant-a", PAYMENT);
                }));
            }
            start.countDown();
            for (Future<HttpResponse<byte[]>> f : futures) {
                responses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(executions.of(key)).isEqualTo(1);
        List<HttpResponse<byte[]>> executed = responses.stream()
                .filter(r -> r.statusCode() == 201 && r.headers().firstValue("Idempotent-Replayed").isEmpty())
                .toList();
        assertThat(executed).hasSize(1);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.statusCode()).isIn(201, 409);
            if (r.statusCode() == 409) {
                assertThat(r.headers().firstValue("Retry-After")).hasValue("1");
            } else {
                assertThat(r.body()).isEqualTo(executed.get(0).body());
            }
        });
        assertThat(responses).anyMatch(r -> r.statusCode() == 409);

        HttpResponse<byte[]> afterwards = post("/payments?delayMs=400", key, "tenant-a", PAYMENT);
        assertThat(afterwards.body()).isEqualTo(executed.get(0).body());
        assertThat(executions.of(key)).isEqualTo(1);
    }

    @Test
    void clientErrorsAreStoredLikeSuccesses() throws Exception {
        HttpResponse<byte[]> first = post("/payments/declined", key, "tenant-a", "{}");
        HttpResponse<byte[]> retry = post("/payments/declined", key, "tenant-a", "{}");
        assertThat(retry.statusCode()).isEqualTo(402);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(executions.of(key)).isEqualTo(1);
    }

    @Test
    void serverErrorsAreNotStoredSoTheClientCanRetry() throws Exception {
        assertThat(post("/payments/unavailable", key, "tenant-a", "{}").statusCode()).isEqualTo(503);
        assertThat(post("/payments/unavailable", key, "tenant-a", "{}").statusCode()).isEqualTo(503);
        assertThat(executions.of(key)).isEqualTo(2);
    }

    @Test
    void aHandlerThatThrowsReleasesTheKey() throws Exception {
        assertThat(post("/payments/boom", key, "tenant-a", "{}").statusCode()).isEqualTo(500);
        assertThat(post("/payments/boom", key, "tenant-a", "{}").statusCode()).isEqualTo(500);
        assertThat(executions.of(key)).isEqualTo(2);
    }

    @Test
    void keysAreScopedPerTenant() throws Exception {
        HttpResponse<byte[]> a = post("/payments", key, "tenant-a", PAYMENT);
        HttpResponse<byte[]> b = post("/payments", key, "tenant-b", PAYMENT);
        assertThat(b.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(b.body()).isNotEqualTo(a.body());
        assertThat(executions.of(key)).isEqualTo(2);
    }

    @Test
    void requestsWithoutAKeyRunNormallyUnlessTheEndpointRequiresOne() throws Exception {
        assertThat(post("/payments", null, "tenant-a", PAYMENT).statusCode()).isEqualTo(201);
        HttpResponse<byte[]> refund = post("/refunds", null, "tenant-a", "{}");
        assertThat(refund.statusCode()).isEqualTo(400);
        assertThat(text(refund)).contains("requires an Idempotency-Key header");
    }

    @Test
    void overlongKeysAreRejected() throws Exception {
        HttpResponse<byte[]> r = post("/payments", "k".repeat(256), "tenant-a", PAYMENT);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(executions.of("k".repeat(256))).isZero();
    }

    @Test
    void endpointsWithoutTheAnnotationIgnoreTheKey() throws Exception {
        post("/not-idempotent", key, "tenant-a", "{}");
        HttpResponse<byte[]> second = post("/not-idempotent", key, "tenant-a", "{}");
        assertThat(text(second)).isEqualTo("run 2");
    }

    @Test
    void includePathsMakeEndpointsIdempotentWithoutTheAnnotation() throws Exception {
        post("/api/included/orders", key, "tenant-a", "{}");
        HttpResponse<byte[]> second = post("/api/included/orders", key, "tenant-a", "{}");
        assertThat(text(second)).isEqualTo("run 1");
        assertThat(second.headers().firstValue("Idempotent-Replayed")).hasValue("true");
    }

    @Test
    void expiredKeysRunAgain() throws Exception {
        assertThat(text(post("/short-lived", key, "tenant-a", "{}"))).isEqualTo("run 1");
        assertThat(text(post("/short-lived", key, "tenant-a", "{}"))).isEqualTo("run 1");
        Thread.sleep(700);
        assertThat(text(post("/short-lived", key, "tenant-a", "{}"))).isEqualTo("run 2");
    }

    @Test
    void formPostsAreFingerprintedByTheirParameters() throws Exception {
        HttpResponse<byte[]> first = send("/forms", key, "tenant-a", "amount=10&currency=EUR",
                "application/x-www-form-urlencoded");
        HttpResponse<byte[]> retry = send("/forms", key, "tenant-a", "currency=EUR&amount=10",
                "application/x-www-form-urlencoded");
        HttpResponse<byte[]> changed = send("/forms", key, "tenant-a", "amount=11&currency=EUR",
                "application/x-www-form-urlencoded");
        assertThat(text(first)).isEqualTo("amount 10 run 1");
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(changed.statusCode()).isEqualTo(422);
    }

    HttpResponse<byte[]> post(String path, String idempotencyKey, String tenant, String json) throws Exception {
        return send(path, idempotencyKey, tenant, json, "application/json");
    }

    HttpResponse<byte[]> send(String path, String idempotencyKey, String tenant, String body, String contentType)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", contentType)
                .header("X-Tenant-Id", tenant)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }
}
