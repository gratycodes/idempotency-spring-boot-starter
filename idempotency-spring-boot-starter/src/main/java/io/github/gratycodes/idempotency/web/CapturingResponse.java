package io.github.gratycodes.idempotency.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Buffers the response so it can be stored before it is sent. Remembers {@code sendError}, because
 * the error body is rendered later by the container's error page and is not visible here; such
 * responses are released instead of stored.
 */
class CapturingResponse extends ContentCachingResponseWrapper {

    private boolean errorSent;

    CapturingResponse(HttpServletResponse response) {
        super(response);
    }

    @Override
    public void sendError(int sc) throws IOException {
        errorSent = true;
        super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        errorSent = true;
        super.sendError(sc, msg);
    }

    boolean isErrorSent() {
        return errorSent;
    }
}
