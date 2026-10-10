package io.github.gratycodes.idempotency.web;

import io.github.gratycodes.idempotency.Idempotent;
import io.github.gratycodes.idempotency.core.IdempotencyEngine;
import io.github.gratycodes.idempotency.core.IdempotencyEngine.Decision;
import io.github.gratycodes.idempotency.core.RequestFingerprinter;
import io.github.gratycodes.idempotency.core.StoredResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Decides per handler whether a request is idempotent, claims the key, and answers duplicates
 * (replay, 409, 422) without calling the handler.
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IdempotencyEngine engine;
    private final IdempotencyWebSettings settings;
    private final IdempotencyScopeResolver scopeResolver;
    private final RequestFingerprinter fingerprinter;
    private final List<PathPattern> includePatterns;

    public IdempotencyInterceptor(IdempotencyEngine engine, IdempotencyWebSettings settings,
            IdempotencyScopeResolver scopeResolver) {
        this.engine = engine;
        this.settings = settings;
        this.scopeResolver = scopeResolver;
        this.fingerprinter = new RequestFingerprinter(settings.fingerprintHeaders());
        this.includePatterns = settings.includePaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod) || !settings.methods().contains(request.getMethod())) {
            return true;
        }
        Idempotent annotation = findAnnotation(handlerMethod);
        if (annotation == null && !matchesIncludedPath(request)) {
            return true;
        }

        String key = request.getHeader(settings.headerName());
        if (key == null) {
            if (keyRequired(annotation)) {
                engine.rejected();
                ProblemResponses.write(response, 400, "Bad Request",
                        "This endpoint requires an " + settings.headerName() + " header.");
                return false;
            }
            return true;
        }
        if (key.isBlank() || key.length() > settings.maxKeyLength()) {
            engine.rejected();
            ProblemResponses.write(response, 400, "Bad Request",
                    settings.headerName() + " must be 1 to " + settings.maxKeyLength() + " characters.");
            return false;
        }

        BufferedBodyRequest buffered = WebUtils.getNativeRequest(request, BufferedBodyRequest.class);
        if (buffered == null) {
            throw new IllegalStateException("IdempotencyFilter is not in the filter chain for " + request.getRequestURI());
        }
        String query = request.getQueryString();
        String fingerprint = fingerprinter.fingerprint(request.getMethod(),
                request.getRequestURI() + (query == null ? "" : "?" + query), request.getContentType(),
                buffered.fingerprintBody(), request::getHeader);
        String scope = scopeResolver.resolve(request);

        Decision decision = engine.begin(scope, key, fingerprint);
        switch (decision) {
            case Decision.Proceed proceed -> {
                request.setAttribute(IdempotencyFilter.CLAIM_ATTRIBUTE,
                        new IdempotencyFilter.Claim(scope, key, proceed.token(), ttl(annotation)));
                return true;
            }
            case Decision.Replay replay -> {
                replay(response, replay.response());
                return false;
            }
            case Decision.InProgress inProgress -> {
                response.setHeader("Retry-After", Long.toString(Math.max(1, ceilSeconds(settings.retryAfter()))));
                ProblemResponses.write(response, 409, "Conflict",
                        "A request with this " + settings.headerName() + " is still being processed. Retry later.");
                return false;
            }
            case Decision.Mismatch mismatch -> {
                ProblemResponses.write(response, 422, "Unprocessable Content",
                        "This " + settings.headerName() + " was already used with a different request.");
                return false;
            }
        }
    }

    private static void replay(HttpServletResponse response, StoredResponse stored) throws java.io.IOException {
        response.setStatus(stored.status());
        for (StoredResponse.Header header : stored.headers()) {
            if (header.name().equalsIgnoreCase("Content-Type")) {
                response.setContentType(header.value());
            } else {
                response.addHeader(header.name(), header.value());
            }
        }
        response.setHeader(REPLAYED_HEADER, "true");
        byte[] body = stored.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static Idempotent findAnnotation(HandlerMethod handlerMethod) {
        Idempotent onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), Idempotent.class);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), Idempotent.class);
    }

    private boolean matchesIncludedPath(HttpServletRequest request) {
        if (includePatterns.isEmpty()) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        PathContainer container = PathContainer.parsePath(path);
        return includePatterns.stream().anyMatch(p -> p.matches(container));
    }

    private boolean keyRequired(Idempotent annotation) {
        if (annotation == null || annotation.key() == Idempotent.KeyRequirement.DEFAULT) {
            return settings.requireKey();
        }
        return annotation.key() == Idempotent.KeyRequirement.REQUIRED;
    }

    private Duration ttl(Idempotent annotation) {
        if (annotation == null || annotation.ttl().isBlank()) {
            return settings.ttl();
        }
        return DurationStyle.detectAndParse(annotation.ttl());
    }

    private static long ceilSeconds(Duration d) {
        return (d.toMillis() + 999) / 1000;
    }
}
