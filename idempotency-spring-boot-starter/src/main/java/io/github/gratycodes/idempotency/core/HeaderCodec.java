package io.github.gratycodes.idempotency.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes stored headers as {@code name: value} lines. HTTP header values cannot contain CR or LF,
 * so a newline is a safe separator.
 */
public final class HeaderCodec {

    private HeaderCodec() {}

    public static String encode(List<StoredResponse.Header> headers) {
        StringBuilder sb = new StringBuilder();
        for (StoredResponse.Header h : headers) {
            if (h.name().indexOf(':') >= 0 || containsLineBreak(h.name()) || containsLineBreak(h.value())) {
                throw new IllegalArgumentException("Header cannot be stored: " + h.name());
            }
            sb.append(h.name()).append(": ").append(h.value()).append('\n');
        }
        return sb.toString();
    }

    public static List<StoredResponse.Header> decode(String encoded) {
        List<StoredResponse.Header> headers = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return headers;
        }
        for (String line : encoded.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("Malformed stored header line");
            }
            String value = line.substring(colon + 1);
            headers.add(new StoredResponse.Header(line.substring(0, colon), value.startsWith(" ") ? value.substring(1) : value));
        }
        return headers;
    }

    private static boolean containsLineBreak(String s) {
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
    }
}
