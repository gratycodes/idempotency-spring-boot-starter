package io.github.gratycodes.idempotency.store;

import io.github.gratycodes.idempotency.core.AcquireResult;
import io.github.gratycodes.idempotency.core.HeaderCodec;
import io.github.gratycodes.idempotency.core.IdempotencyStore;
import io.github.gratycodes.idempotency.core.StoredResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis store. Each key is a hash with a Redis TTL: the lock timeout while in flight, the replay TTL
 * once completed. Expiry is Redis's job, so a crashed request's lock disappears on its own.
 *
 * <p>Every operation is one Lua script, so claim-if-absent (the hash equivalent of
 * {@code SET key token NX PX timeout}) and compare-token-then-write cannot interleave with another
 * client. Bodies are stored base64-encoded.
 *
 * <p>Redis replication is asynchronous: after a failover a replica may not have the latest claims.
 * If that matters, use the JDBC store.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ACQUIRE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
              redis.call('HSET', KEYS[1], 'state', 'IN_FLIGHT', 'fp', ARGV[1], 'token', ARGV[2])
              redis.call('PEXPIRE', KEYS[1], ARGV[3])
              return {'ACQUIRED'}
            end
            local v = redis.call('HMGET', KEYS[1], 'state', 'fp', 'status', 'headers', 'body')
            return {v[1] or '', v[2] or '', v[3] or '', v[4] or '', v[5] or ''}
            """, List.class);

    private static final RedisScript<Long> COMPLETE = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'state') == 'IN_FLIGHT' and redis.call('HGET', KEYS[1], 'token') == ARGV[1] then
              redis.call('HSET', KEYS[1], 'state', 'COMPLETED', 'status', ARGV[2], 'headers', ARGV[3], 'body', ARGV[4])
              redis.call('HDEL', KEYS[1], 'token')
              redis.call('PEXPIRE', KEYS[1], ARGV[5])
              return 1
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'state') == 'IN_FLIGHT' and redis.call('HGET', KEYS[1], 'token') == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisIdempotencyStore(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AcquireResult tryAcquire(String scope, String key, String fingerprint, Duration lockTimeout) {
        String token = UUID.randomUUID().toString();
        List<Object> r = redis.execute(ACQUIRE, List.of(redisKey(scope, key)), fingerprint, token,
                Long.toString(Math.max(1, lockTimeout.toMillis())));
        if (r == null || r.isEmpty()) {
            throw new IllegalStateException("Unexpected empty reply from Redis");
        }
        String state = (String) r.get(0);
        if ("ACQUIRED".equals(state)) {
            return new AcquireResult.Acquired(token);
        }
        String fp = (String) r.get(1);
        if ("COMPLETED".equals(state)) {
            byte[] body = Base64.getDecoder().decode((String) r.get(4));
            return new AcquireResult.Completed(fp,
                    new StoredResponse(Integer.parseInt((String) r.get(2)), HeaderCodec.decode((String) r.get(3)), body));
        }
        return new AcquireResult.InFlight(fp);
    }

    @Override
    public boolean complete(String scope, String key, String token, StoredResponse response, Duration ttl) {
        Long r = redis.execute(COMPLETE, List.of(redisKey(scope, key)), token, Integer.toString(response.status()),
                HeaderCodec.encode(response.headers()), Base64.getEncoder().encodeToString(response.body()),
                Long.toString(Math.max(1, ttl.toMillis())));
        return r != null && r == 1L;
    }

    @Override
    public boolean release(String scope, String key, String token) {
        Long r = redis.execute(RELEASE, List.of(redisKey(scope, key)), token);
        return r != null && r == 1L;
    }

    String redisKey(String scope, String key) {
        // Length-prefixed so a scope containing ':' cannot collide with another scope's key.
        return prefix + scope.length() + ":" + scope + ":" + key;
    }
}
