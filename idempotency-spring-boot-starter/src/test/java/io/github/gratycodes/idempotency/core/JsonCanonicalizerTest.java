package io.github.gratycodes.idempotency.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonCanonicalizerTest {

    private static String canon(String json) {
        return JsonCanonicalizer.canonicalize(json.getBytes(StandardCharsets.UTF_8))
                .map(b -> new String(b, StandardCharsets.UTF_8))
                .orElse(null);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"b\":1,\"a\":2}                          | {\"a\":2,\"b\":1}",
            "  { \"a\" : [ 1 , 2 , {\"z\":null,\"y\":true} ] }  | {\"a\":[1,2,{\"y\":true,\"z\":null}]}",
            "\"caf\\u00e9\"                             | \"café\"",
            "\"tab\\there\"                             | \"tab\\u0009here\"",
            "\"slash\\/quote\\\"\"                      | \"slash/quote\\\"\"",
            "-12.50e+3                                  | -12.50e+3",
            "[]                                         | []",
            "{}                                         | {}"})
    void canonicalizes(String input, String expected) {
        assertThat(canon(input.trim())).isEqualTo(expected.trim());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"a\":1,}", "[1 2]", "{\"a\":1,\"a\":2}", "01", "tru", "{\"a\":1} x", "\"unterminated",
            "1.", "{a:1}"})
    void rejectsInvalidOrAmbiguousJson(String input) {
        assertThat(canon(input)).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"1 | 1.0", "\"1\" | 1", "{\"a\":[1,2]} | {\"a\":[2,1]}"})
    void keepsDifferencesThatMayMatter(String a, String b) {
        assertThat(canon(a.trim())).isNotEqualTo(canon(b.trim()));
    }

    @org.junit.jupiter.api.Test
    void deeplyNestedInputIsRejectedNotOverflowed() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        assertThat(canon(deep)).isNull();
    }
}
