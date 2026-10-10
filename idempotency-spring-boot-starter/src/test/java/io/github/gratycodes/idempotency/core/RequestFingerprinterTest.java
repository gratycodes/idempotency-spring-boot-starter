package io.github.gratycodes.idempotency.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestFingerprinterTest {

    private final RequestFingerprinter plain = new RequestFingerprinter(List.of());

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void jsonKeyOrderAndWhitespaceDoNotChangeTheFingerprint() {
        String a = plain.fingerprint("POST", "/payments", "application/json", b("{\"amount\":1000,\"currency\":\"EUR\"}"), Map.of());
        String c = plain.fingerprint("post", "/payments", "application/json; charset=UTF-8",
                b("{\n  \"currency\": \"EUR\",\n  \"amount\": 1000\n}"), Map.of());
        assertThat(a).isEqualTo(c).hasSize(64);
    }

    @Test
    void differentAmountMethodPathOrQueryChangeTheFingerprint() {
        String base = plain.fingerprint("POST", "/payments", "application/json", b("{\"amount\":1000}"), Map.of());
        assertThat(plain.fingerprint("POST", "/payments", "application/json", b("{\"amount\":1001}"), Map.of())).isNotEqualTo(base);
        assertThat(plain.fingerprint("PATCH", "/payments", "application/json", b("{\"amount\":1000}"), Map.of())).isNotEqualTo(base);
        assertThat(plain.fingerprint("POST", "/refunds", "application/json", b("{\"amount\":1000}"), Map.of())).isNotEqualTo(base);
        assertThat(plain.fingerprint("POST", "/payments?capture=false", "application/json", b("{\"amount\":1000}"), Map.of())).isNotEqualTo(base);
    }

    @Test
    void nonJsonBodiesAreComparedRawly() {
        String a = plain.fingerprint("POST", "/upload", "text/plain", b("a b"), Map.of());
        String c = plain.fingerprint("POST", "/upload", "text/plain", b("a  b"), Map.of());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void selectedHeadersArePartOfTheFingerprintCaseInsensitively() {
        RequestFingerprinter withHeader = new RequestFingerprinter(List.of("X-Account"));
        String a = withHeader.fingerprint("POST", "/p", null, new byte[0], Map.of("x-account", "acct_1"));
        String c = withHeader.fingerprint("POST", "/p", null, new byte[0], Map.of("X-ACCOUNT", "acct_1"));
        String d = withHeader.fingerprint("POST", "/p", null, new byte[0], Map.of("X-Account", "acct_2"));
        assertThat(a).isEqualTo(c).isNotEqualTo(d);
        assertThat(plain.fingerprint("POST", "/p", null, new byte[0], Map.of("X-Account", "acct_2")))
                .isEqualTo(plain.fingerprint("POST", "/p", null, new byte[0], Map.of()));
    }

    @Test
    void fieldBoundariesCannotBeShifted() {
        assertThat(plain.fingerprint("POST", "/ab", null, b("c"), Map.of()))
                .isNotEqualTo(plain.fingerprint("POST", "/a", null, b("bc"), Map.of()));
    }

    @Test
    void recognisesJsonMediaTypes() {
        assertThat(RequestFingerprinter.isJson("application/json")).isTrue();
        assertThat(RequestFingerprinter.isJson("application/merge-patch+json; charset=utf-8")).isTrue();
        assertThat(RequestFingerprinter.isJson("text/json-ish")).isFalse();
        assertThat(RequestFingerprinter.isJson(null)).isFalse();
    }
}
