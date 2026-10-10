package io.github.gratycodes.idempotency.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Rewrites a JSON document into a canonical form for fingerprinting: object keys sorted, no
 * insignificant whitespace, strings re-escaped the same way. Numbers keep their original text, so
 * {@code 1} and {@code 1.0} are still different requests.
 *
 * <p>Returns empty for anything that is not a single valid JSON value (or has duplicate object keys);
 * callers then hash the raw bytes instead.
 */
public final class JsonCanonicalizer {

    private static final int MAX_DEPTH = 256;

    private final String in;
    private int pos;

    private JsonCanonicalizer(String in) {
        this.in = in;
    }

    public static Optional<byte[]> canonicalize(byte[] json) {
        try {
            JsonCanonicalizer p = new JsonCanonicalizer(new String(json, StandardCharsets.UTF_8));
            p.skipWhitespace();
            StringBuilder out = new StringBuilder(json.length);
            p.value(out, 0);
            p.skipWhitespace();
            if (p.pos != p.in.length()) {
                return Optional.empty();
            }
            return Optional.of(out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    private void value(StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("too deep");
        }
        char c = in.charAt(pos);
        switch (c) {
            case '{' -> object(out, depth);
            case '[' -> array(out, depth);
            case '"' -> writeString(out, string());
            case 't' -> literal(out, "true");
            case 'f' -> literal(out, "false");
            case 'n' -> literal(out, "null");
            default -> number(out);
        }
    }

    private void object(StringBuilder out, int depth) {
        pos++;
        Map<String, String> members = new TreeMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            out.append("{}");
            return;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new IllegalArgumentException("expected key");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            StringBuilder member = new StringBuilder();
            value(member, depth + 1);
            if (members.put(key, member.toString()) != null) {
                throw new IllegalArgumentException("duplicate key");
            }
            skipWhitespace();
            char c = in.charAt(pos++);
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or }");
            }
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : members.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, e.getKey());
            out.append(':').append(e.getValue());
        }
        out.append('}');
    }

    private void array(StringBuilder out, int depth) {
        pos++;
        List<String> items = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            out.append("[]");
            return;
        }
        while (true) {
            skipWhitespace();
            StringBuilder item = new StringBuilder();
            value(item, depth + 1);
            items.add(item.toString());
            skipWhitespace();
            char c = in.charAt(pos++);
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or ]");
            }
        }
        out.append('[').append(String.join(",", items)).append(']');
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = in.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = in.charAt(pos++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(in.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("bad escape");
            }
        }
    }

    private void number(StringBuilder out) {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        digits(true);
        if (pos < in.length() && in.charAt(pos) == '.') {
            pos++;
            digits(false);
        }
        if (pos < in.length() && (in.charAt(pos) == 'e' || in.charAt(pos) == 'E')) {
            pos++;
            if (in.charAt(pos) == '+' || in.charAt(pos) == '-') {
                pos++;
            }
            digits(false);
        }
        out.append(in, start, pos);
    }

    private void digits(boolean integerPart) {
        int start = pos;
        while (pos < in.length() && Character.isDigit(in.charAt(pos)) && in.charAt(pos) < 128) {
            pos++;
        }
        if (pos == start || (integerPart && pos - start > 1 && in.charAt(start) == '0')) {
            throw new IllegalArgumentException("bad number");
        }
    }

    private void literal(StringBuilder out, String word) {
        if (!in.startsWith(word, pos)) {
            throw new IllegalArgumentException("bad literal");
        }
        pos += word.length();
        out.append(word);
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private char peek() {
        return in.charAt(pos);
    }

    private void expect(char c) {
        if (in.charAt(pos++) != c) {
            throw new IllegalArgumentException("expected " + c);
        }
    }

    private void skipWhitespace() {
        while (pos < in.length()) {
            char c = in.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }
}
