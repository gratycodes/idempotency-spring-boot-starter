package io.github.gratycodes.idempotency.core;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The parts of a completed HTTP response that are replayed on retries: status, a chosen set of
 * headers and the exact body bytes.
 */
public record StoredResponse(int status, List<Header> headers, byte[] body) {

    public StoredResponse {
        headers = List.copyOf(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public record Header(String name, String value) {
        public Header {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StoredResponse other
                && status == other.status
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, headers, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "StoredResponse[status=" + status + ", headers=" + headers + ", body=" + body.length + " bytes]";
    }
}
