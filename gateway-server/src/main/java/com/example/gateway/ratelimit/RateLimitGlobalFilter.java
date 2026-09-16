package com.example.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 多策略限流全局过滤器，校验顺序：
 * <ol>
 *     <li>IP 白名单：命中直接放行，跳过所有限流；</li>
 *     <li>全局限流：所有请求共享令牌桶；</li>
 *     <li>接口限流：按路由 + 请求路径维度分别计数；</li>
 *     <li>用户限流：按请求头 X-User-Id 维度分别计数（无头则跳过）。</li>
 * </ol>
 * 任一维度拒绝即抛出 {@link RateLimitExceededException}（HTTP 429），
 * 由全局异常处理器统一返回标准 JSON。
 */
@Component
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    /** 在路由匹配（RouteToRequestUrlFilter, ORDER 10000）之后、转发之前执行。 */
    public static final int ORDER = 10100;

    public static final String USER_HEADER = "X-User-Id";
    public static final String GLOBAL_KEY = "rl:global";

    private static final Logger log = LoggerFactory.getLogger(RateLimitGlobalFilter.class);

    private final RateLimitProperties properties;
    private final TokenBucketService tokenBucketService;
    private final List<IpCidrMatcher> whitelistMatchers;

    public RateLimitGlobalFilter(RateLimitProperties properties, TokenBucketService tokenBucketService) {
        this.properties = properties;
        this.tokenBucketService = tokenBucketService;
        this.whitelistMatchers = properties.getIpWhitelist().stream()
                .map(rule -> {
                    try {
                        return IpCidrMatcher.compile(rule);
                    } catch (IllegalArgumentException e) {
                        log.warn("忽略非法的 IP 白名单规则: {} ({})", rule, e.getMessage());
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        InetAddress clientAddress = resolveClientAddress(request);

        // 1. IP 白名单：命中则完全不限流
        if (clientAddress != null && isWhitelisted(clientAddress)) {
            log.debug("客户端 IP {} 命中白名单，跳过限流", clientAddress.getHostAddress());
            return chain.filter(exchange);
        }

        // 2. 全局限流
        RateLimitProperties.Bucket globalBucket = properties.getGlobal();
        Mono<RateLimitContext> chainMono = Mono.just(new RateLimitContext());
        if (globalBucket != null) {
            chainMono = chainMono.flatMap(ctx -> applyBucket(exchange, ctx, "global", GLOBAL_KEY, globalBucket));
        }

        // 3. 接口限流（路由 + 路径）
        String routeId = exchange.getAttributeOrDefault(
                org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_ROUTE_ATTR,
                "default");
        String path = request.getPath().value();
        RateLimitProperties.Bucket apiBucket = properties.getApi();
        if (apiBucket != null) {
            String apiKey = "rl:api:" + routeId + ":" + path;
            chainMono = chainMono.flatMap(ctx -> applyBucket(exchange, ctx, "api", apiKey, apiBucket));
        }

        // 4. 用户限流（X-User-Id）
        String userId = request.getHeaders().getFirst(USER_HEADER);
        RateLimitProperties.Bucket userBucket = properties.getUser();
        if (userBucket != null && userId != null && !userId.isBlank()) {
            String userKey = "rl:user:" + userId.trim();
            chainMono = chainMono.flatMap(ctx -> applyBucket(exchange, ctx, "user:" + userId.trim(), userKey, userBucket));
        }

        return chainMono.then(chain.filter(exchange));
    }

    private Mono<RateLimitContext> applyBucket(ServerWebExchange exchange, RateLimitContext ctx,
                                               String dimension, String key,
                                               RateLimitProperties.Bucket bucket) {
        return tokenBucketService.tryAcquire(key, bucket)
                .doOnNext(result -> {
                    ctx.latest = result;
                    HttpHeaders headers = exchange.getResponse().getHeaders();
                    headers.add("X-RateLimit-Limit", String.valueOf(result.limit()));
                    headers.add("X-RateLimit-Remaining", String.valueOf(result.remaining()));
                })
                .flatMap(result -> {
                    if (!result.allowed()) {
                        return Mono.<RateLimitContext>error(
                                new RateLimitExceededException(dimension, key, result));
                    }
                    return Mono.just(ctx);
                });
    }

    private boolean isWhitelisted(InetAddress address) {
        for (IpCidrMatcher matcher : whitelistMatchers) {
            if (matcher.matches(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析客户端 IP。默认只取 TCP 对端地址；开启 trust-forward-header 后优先使用
     * X-Forwarded-For 的首个地址（应仅在受信反向代理后开启）。
     */
    private InetAddress resolveClientAddress(ServerHttpRequest request) {
        if (properties.isTrustForwardHeader()) {
            String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].trim();
                try {
                    return InetAddress.getByName(first);
                } catch (UnknownHostException ignored) {
                    // 回退到远程地址
                }
            }
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress == null ? null : remoteAddress.getAddress();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** 限流链路中透传的上下文（保留最后一次检查结果用于响应头/异常）。 */
    private static class RateLimitContext {
        private RateLimitResult latest;
    }
}
