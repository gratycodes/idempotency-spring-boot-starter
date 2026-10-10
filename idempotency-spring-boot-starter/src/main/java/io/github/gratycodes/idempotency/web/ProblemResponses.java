package io.github.gratycodes.idempotency.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** RFC 9457 problem responses for the cases the starter answers itself. */
final class ProblemResponses {

    private ProblemResponses() {}

    static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
        byte[] body = ("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
