package io.github.gratycodes.idempotency.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("idempotency")
public class IdempotencyProperties {

    /** Turns the whole starter off. */
    private boolean enabled = true;

    /** Request header that carries the key. */
    private String headerName = "Idempotency-Key";

    /** Reject idempotent requests that have no key with 400. Can be overridden per endpoint. */
    private boolean requireKey = false;

    /** Longest accepted key. Stripe allows 255 characters. */
    private int maxKeyLength = 255;

    /** HTTP methods that idempotency applies to. GET, PUT and DELETE are idempotent already. */
    private Set<String> methods = Set.of("POST", "PATCH");

    /**
     * Path patterns (Spring PathPattern syntax, without the context path) treated as idempotent even
     * without {@code @Idempotent}, e.g. {@code /api/**}.
     */
    private List<String> includePaths = new ArrayList<>();

    /** How long a completed response is replayed for. */
    private Duration ttl = Duration.ofHours(24);

    /**
     * How long an in-flight claim lasts before another request may take the key over. Must be longer
     * than your slowest request; it is what recovers keys from crashed instances.
     */
    private Duration lockTimeout = Duration.ofSeconds(30);

    /** Value of Retry-After on a 409 for a request still in progress. */
    private Duration retryAfter = Duration.ofSeconds(1);

    /** Response headers stored and replayed together with status and body. */
    private List<String> replayHeaders = new ArrayList<>(List.of("Content-Type", "Content-Language", "Location"));

    /** Request headers that are part of the fingerprint, in addition to method, path, query and body. */
    private List<String> fingerprintHeaders = new ArrayList<>();

    /** Store 5xx responses for replay (Stripe does). Off by default so clients can retry server errors. */
    private boolean cacheServerErrors = false;

    /** Statuses that are never stored; the key is released and the client may retry. */
    private Set<Integer> neverCacheStatuses = Set.of(408, 429);

    /** Order of the servlet filter. Keep it after Spring Security (-100) when scoping by principal. */
    private int filterOrder = 0;

    private Store store = Store.AUTO;

    private final Scope scope = new Scope();
    private final Jdbc jdbc = new Jdbc();
    private final Redis redis = new Redis();
    private final Webhook webhook = new Webhook();

    public enum Store {
        /** JDBC if a DataSource exists, else Redis if a connection factory exists, else in-memory. */
        AUTO,
        JDBC,
        REDIS,
        MEMORY
    }

    public static class Scope {
        /**
         * Request header that names the tenant or client. Only use a header your gateway sets after
         * authentication; a client-controlled header lets callers share keys across tenants.
         */
        private String header;

        /** Fall back to the authenticated principal's name when the header is absent. */
        private boolean usePrincipal = true;

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public boolean isUsePrincipal() {
            return usePrincipal;
        }

        public void setUsePrincipal(boolean usePrincipal) {
            this.usePrincipal = usePrincipal;
        }
    }

    public static class Jdbc {
        private String table = "idempotency_keys";

        /** Run the bundled CREATE TABLE IF NOT EXISTS script on startup. Prefer Flyway/Liquibase in production. */
        private boolean initializeSchema = false;

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public boolean isInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
        }
    }

    public static class Redis {
        private String keyPrefix = "idempotency:";

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    public static class Webhook {
        private String table = "webhook_inbox";

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }
    public boolean isRequireKey() { return requireKey; }
    public void setRequireKey(boolean requireKey) { this.requireKey = requireKey; }
    public int getMaxKeyLength() { return maxKeyLength; }
    public void setMaxKeyLength(int maxKeyLength) { this.maxKeyLength = maxKeyLength; }
    public Set<String> getMethods() { return methods; }
    public void setMethods(Set<String> methods) { this.methods = methods; }
    public List<String> getIncludePaths() { return includePaths; }
    public void setIncludePaths(List<String> includePaths) { this.includePaths = includePaths; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public Duration getLockTimeout() { return lockTimeout; }
    public void setLockTimeout(Duration lockTimeout) { this.lockTimeout = lockTimeout; }
    public Duration getRetryAfter() { return retryAfter; }
    public void setRetryAfter(Duration retryAfter) { this.retryAfter = retryAfter; }
    public List<String> getReplayHeaders() { return replayHeaders; }
    public void setReplayHeaders(List<String> replayHeaders) { this.replayHeaders = replayHeaders; }
    public List<String> getFingerprintHeaders() { return fingerprintHeaders; }
    public void setFingerprintHeaders(List<String> fingerprintHeaders) { this.fingerprintHeaders = fingerprintHeaders; }
    public boolean isCacheServerErrors() { return cacheServerErrors; }
    public void setCacheServerErrors(boolean cacheServerErrors) { this.cacheServerErrors = cacheServerErrors; }
    public Set<Integer> getNeverCacheStatuses() { return neverCacheStatuses; }
    public void setNeverCacheStatuses(Set<Integer> neverCacheStatuses) { this.neverCacheStatuses = neverCacheStatuses; }
    public int getFilterOrder() { return filterOrder; }
    public void setFilterOrder(int filterOrder) { this.filterOrder = filterOrder; }
    public Store getStore() { return store; }
    public void setStore(Store store) { this.store = store; }
    public Scope getScope() { return scope; }
    public Jdbc getJdbc() { return jdbc; }
    public Redis getRedis() { return redis; }
    public Webhook getWebhook() { return webhook; }
}
