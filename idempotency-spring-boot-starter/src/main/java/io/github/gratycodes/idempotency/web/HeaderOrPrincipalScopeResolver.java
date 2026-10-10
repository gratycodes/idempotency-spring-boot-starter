package io.github.gratycodes.idempotency.web;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;

/** Scope from a trusted header, else the authenticated principal, else {@code anonymous}. */
public class HeaderOrPrincipalScopeResolver implements IdempotencyScopeResolver {

    private final String header;
    private final boolean usePrincipal;

    public HeaderOrPrincipalScopeResolver(String header, boolean usePrincipal) {
        this.header = header == null || header.isBlank() ? null : header;
        this.usePrincipal = usePrincipal;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        if (header != null) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return "h:" + value.trim();
            }
        }
        if (usePrincipal) {
            Principal principal = request.getUserPrincipal();
            if (principal != null && principal.getName() != null) {
                return "p:" + principal.getName();
            }
        }
        return "anonymous";
    }
}
