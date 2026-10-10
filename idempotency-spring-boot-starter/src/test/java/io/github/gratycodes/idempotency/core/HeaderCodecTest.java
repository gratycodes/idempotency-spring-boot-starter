package io.github.gratycodes.idempotency.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeaderCodecTest {

    @Test
    void roundTripsValuesWithColonsAndRepeatedNames() {
        List<StoredResponse.Header> headers = List.of(
                new StoredResponse.Header("Location", "https://api.example.test:8443/payments/pay_1"),
                new StoredResponse.Header("Link", "<a>; rel=next"),
                new StoredResponse.Header("Link", "<b>; rel=prev"),
                new StoredResponse.Header("X-Empty", ""));
        assertThat(HeaderCodec.decode(HeaderCodec.encode(headers))).isEqualTo(headers);
    }

    @Test
    void refusesHeadersThatWouldBreakTheFormat() {
        assertThatThrownBy(() -> HeaderCodec.encode(List.of(new StoredResponse.Header("X", "a\r\nInjected: 1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyInputDecodesToNoHeaders() {
        assertThat(HeaderCodec.decode(null)).isEmpty();
        assertThat(HeaderCodec.decode("")).isEmpty();
    }
}
