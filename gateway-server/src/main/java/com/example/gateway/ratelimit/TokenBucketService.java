package com.example.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 基于 Redis 的响应式令牌桶限流器。
 *
 * <p>每个桶在 Redis 中用两个 key 保存（原子地由 Lua 脚本维护）：
 * <ul>
 *     <li>{@code <key>:tokens}：当前令牌数（整数）；</li>
 *     <li>{@code <key>:ts}：上次填充时间（毫秒）。</li>
 * </ul>
 *
 * Lua 脚本在 Redis 单线程内完成「按时间差补充令牌 → 尝试扣减」，保证并发下计数准确。
 * 脚本同时兼容内嵌 Redis（jedis-mock 内置 Lua 实现）与真实 Redis。
 */
@Service
public class TokenBucketService {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketService.class);

    /**
     * KEYS[1] = 令牌数 key，KEYS[2] = 时间戳 key
     * ARGV    = 容量, 每周期补充令牌, 周期秒数, 当前毫秒时间, 申请令牌数, 过期秒数
     * 返回    = { 是否放行(1/0), 剩余令牌, 拒绝后建议重试秒数 }
     */
    private static final String LUA_SCRIPT = """
            local capacity = tonumber(ARGV[1])
            local refill_tokens = tonumber(ARGV[2])
            local refill_seconds = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            local requested = tonumber(ARGV[5])
            local expire_seconds = tonumber(ARGV[6])

            local tokens = tonumber(redis.call('get', KEYS[1]))
            local last_refill = tonumber(redis.call('get', KEYS[2]))
            if tokens == nil then
                tokens = capacity
                last_refill = now
            end

            local refill_millis = refill_seconds * 1000
            local elapsed = now - last_refill
            if elapsed > 0 and refill_tokens > 0 and refill_millis > 0 then
                local millis_per_token = refill_millis / refill_tokens
                local refilled = math.floor(elapsed / millis_per_token)
                if refilled > 0 then
                    tokens = math.min(capacity, tokens + refilled)
                    -- 仅推进本次新增令牌对应的时间，剩余不足一个令牌的时间继续累积
                    last_refill = last_refill + refilled * millis_per_token
                    if last_refill > now then
                        last_refill = now
                    end
                end
            end

            local allowed = 0
            local retry_after = 0
            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
            else
                if refill_tokens > 0 then
                    local missing = requested - tokens
                    local millis_per_token = refill_millis / refill_tokens
                    retry_after = math.ceil(missing * millis_per_token / 1000)
                    if retry_after < 1 then
                        retry_after = 1
                    end
                else
                    retry_after = expire_seconds
                end
            end

            redis.call('set', KEYS[1], tokens)
            redis.call('set', KEYS[2], last_refill)
            redis.call('expire', KEYS[1], expire_seconds)
            redis.call('expire', KEYS[2], expire_seconds)

            return { allowed, tokens, retry_after }
            """;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> script;

    public TokenBucketService(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, List.class);
    }

    /**
     * 申请 1 个令牌。
     *
     * @param key    限流维度对应的 Redis key 前缀
     * @param bucket 令牌桶参数
     * @return 申请结果
     */
    public Mono<RateLimitResult> tryAcquire(String key, RateLimitProperties.Bucket bucket) {
        List<String> keys = List.of(key + ":tokens", key + ":ts");
        long now = System.currentTimeMillis();
        Flux<List> flux = redisTemplate.execute(
                script,
                keys,
                String.valueOf(bucket.getCapacity()),
                String.valueOf(bucket.getRefillTokens()),
                String.valueOf(bucket.getRefillSeconds()),
                String.valueOf(now),
                "1",
                String.valueOf(bucket.getExpireSeconds()));
        return flux.next()
                .<RateLimitResult>map(result -> {
                    boolean allowed = toLong(result.get(0)) != 0L;
                    long remaining = toLong(result.get(1));
                    long retryAfter = toLong(result.get(2));
                    return allowed
                            ? RateLimitResult.allowed(bucket.getCapacity(), remaining)
                            : RateLimitResult.rejected(bucket.getCapacity(), remaining, retryAfter);
                })
                .onErrorResume(e -> {
                    // Redis 故障时 fail-open，避免限流组件故障导致网关整体不可用
                    log.error("令牌桶限流判断异常，放行请求: key={}", key, e);
                    return Mono.just(RateLimitResult.allowed(bucket.getCapacity(), bucket.getCapacity()));
                });
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }
}
