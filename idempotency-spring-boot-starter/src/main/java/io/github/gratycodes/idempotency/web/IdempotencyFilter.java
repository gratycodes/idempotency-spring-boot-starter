package io.github.gratycodes.idempotency.web;

import io.github.gratycodes.idempotency.core.IdempotencyEngine;
import io.github.gratycodes.idempotency.core.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Buffers request and response for requests that carry an idempotency key, and stores the response
 * after the handler finished. The decision whether a request is idempotent, and the claim itself,
 * happen in {@link IdempotencyInterceptor}, which knows the handler method.
 */
public class IdempotencyFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    static final String CLAIM_ATTRIBUTE = IdempotencyFilter.class.getName() + ".claim";

    record Claim(String scope, String key, String token, Duration ttl) {}

    private final IdempotencyEngine engine;
    private final IdempotencyWebSettings settings;
    private final int order;

    public IdempotencyFilter(IdempotencyEngine engine, IdempotencyWebSettings settings, int order) {
        this.engine = engine;
        this.settings = settings;
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !settings.methods().contains(request.getMethod()) || request.getHeader(settings.headerName()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        BufferedBodyRequest bufferedRequest = new BufferedBodyRequest(request);
        CapturingResponse capturedResponse = new CapturingResponse(response);
        try {
            chain.doFilter(bufferedRequest, capturedResponse);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            Claim claim = takeClaim(bufferedRequest);
            if (claim != null) {
                safely(() -> engine.abort(claim.scope(), claim.key(), claim.token()));
            }
            throw e;
        }

        Claim claim = takeClaim(bufferedRequest);
        if (claim != null) {
            if (bufferedRequest.isAsyncStarted()) {
                log.warn("Async handler on idempotent request {} {}: response not stored; the key stays locked "
                        + "until idempotency.lock-timeout", request.getMethod(), request.getRequestURI());
            } else if (capturedResponse.isErrorSent()) {
                safely(() -> engine.abort(claim.scope(), claim.key(), claim.token()));
            } else {
                StoredResponse stored = capture(capturedResponse);
                safely(() -> engine.finish(claim.scope(), claim.key(), claim.token(), stored, claim.ttl()));
            }
        }
        capturedResponse.copyBodyToResponse();
    }

    private StoredResponse capture(CapturingResponse response) {
        List<StoredResponse.Header> headers = new ArrayList<>();
        for (String name : settings.replayHeaders()) {
            if (name.equalsIgnoreCase("Content-Type")) {
                if (response.getContentType() != null) {
                    headers.add(new StoredResponse.Header("Content-Type", response.getContentType()));
                }
                continue;
            }
            for (String value : response.getHeaders(name)) {
                headers.add(new StoredResponse.Header(name, value));
            }
        }
        return new StoredResponse(response.getStatus(), headers, response.getContentAsByteArray());
    }

    private static Claim takeClaim(HttpServletRequest request) {
        Object claim = request.getAttribute(CLAIM_ATTRIBUTE);
        request.removeAttribute(CLAIM_ATTRIBUTE);
        return claim instanceof Claim c ? c : null;
    }

    private static void safely(Runnable storeCall) {
        // The handler already ran and its response is about to be sent. A store failure here must not
        // turn a successful operation into an error; the key will expire after the lock timeout.
        try {
            storeCall.run();
        } catch (RuntimeException e) {
            log.error("Could not record idempotent response; the key stays locked until it expires", e);
        }
    }
}
