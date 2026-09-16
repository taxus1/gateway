package com.example.gateway.ratelimit;

import org.springframework.http.HttpStatus;

/**
 * 触发限流时抛出，由全局异常处理器转换为 HTTP 429 响应。
 */
public class RateLimitExceededException extends RuntimeException {

    private final String dimension;
    private final String dimensionKey;
    private final RateLimitResult result;

    public RateLimitExceededException(String dimension, String dimensionKey, RateLimitResult result) {
        super("请求过于频繁: dimension=" + dimension + ", key=" + dimensionKey);
        this.dimension = dimension;
        this.dimensionKey = dimensionKey;
        this.result = result;
    }

    public String getDimension() {
        return dimension;
    }

    public String getDimensionKey() {
        return dimensionKey;
    }

    public RateLimitResult getResult() {
        return result;
    }

    public HttpStatus getStatus() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }
}
