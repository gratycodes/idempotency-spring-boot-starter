package io.github.gratycodes.idempotency.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * SHA-256 over the parts of a request that define "the same request": method, path and query,
 * selected headers, and the body (JSON bodies are canonicalized first, so key order and whitespace
 * do not matter).
 */
public final class RequestFingerprinter {

    private final List<String> headerNames;

    public RequestFingerprinter(List<String> headerNames) {
        this.headerNames = headerNames.stream().map(h -> h.toLowerCase(Locale.ROOT)).sorted().distinct().toList();
    }

    public String fingerprint(String method, String pathAndQuery, String contentType, byte[] body,
            Function<String, String> headerLookup) {
        MessageDigest digest = sha256();
        field(digest, method.toUpperCase(Locale.ROOT));
        field(digest, pathAndQuery);
        for (String name : headerNames) {
            String value = headerLookup.apply(name);
            field(digest, name + "=" + (value == null ? "" : value));
        }
        digest.update(normalizeBody(contentType, body));
        return HexFormat.of().formatHex(digest.digest());
    }

    static byte[] normalizeBody(String contentType, byte[] body) {
        if (body.length > 0 && isJson(contentType)) {
            return JsonCanonicalizer.canonicalize(body).orElse(body);
        }
        return body;
    }

    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.equals("application/json") || type.endsWith("+json");
    }

    private static void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // Length-prefix every field so ("ab","c") and ("a","bc") cannot collide.
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Convenience for tests and non-servlet callers. */
    public String fingerprint(String method, String pathAndQuery, String contentType, byte[] body,
            Map<String, String> headers) {
        return fingerprint(method, pathAndQuery, contentType, body, name -> {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        });
    }
}
