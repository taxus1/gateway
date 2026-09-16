package com.example.gateway.ratelimit;

/**
 * 一次令牌桶申请的结果。
 *
 * @param allowed     是否放行
 * @param limit       桶容量
 * @param remaining   剩余令牌
 * @param retryAfterSeconds 被拒绝时建议的重试间隔（秒）
 */
public record RateLimitResult(boolean allowed, long limit, long remaining, long retryAfterSeconds) {

    public static RateLimitResult allowed(long limit, long remaining) {
        return new RateLimitResult(true, limit, remaining, 0);
    }

    public static RateLimitResult rejected(long limit, long remaining, long retryAfterSeconds) {
        return new RateLimitResult(false, limit, remaining, retryAfterSeconds);
    }
}
