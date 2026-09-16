package com.example.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 限流配置，前缀 {@code app.rate-limit}。
 *
 * <ul>
 *     <li>{@link #enabled}：总开关；</li>
 *     <li>{@link #ipWhitelist}：IP 白名单（支持单 IP，如 127.0.0.1，以及 CIDR，如 10.0.0.0/8），命中则完全跳过限流；</li>
 *     <li>{@link #trustForwardHeader}：是否信任 X-Forwarded-For 取客户端 IP，默认关闭（防伪造），生产应在反向代理后开启；</li>
 *     <li>{@link #global}：全局限流，所有请求共享一个桶；</li>
 *     <li>{@link #api}：接口限流，按网关路由维度（routeId + 完整路径）分别计数；</li>
 *     <li>{@link #user}：用户限流，按请求头 {@code X-User-Id} 维度计数，未携带该头则不做用户限流。</li>
 * </ul>
 *
 * 每个桶均为令牌桶算法：{@code capacity} 桶容量（突发上限），{@code refillTokens} / {@code refillSeconds}
 * 表示每 N 秒补充多少令牌；{@code expireSeconds} 为桶在 Redis 中的空闲过期时间。
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /** 限流总开关。 */
    private boolean enabled = true;

    /** IP 白名单，支持单 IP 与 CIDR。 */
    private List<String> ipWhitelist = List.of();

    /** 是否信任 X-Forwarded-For 请求头。 */
    private boolean trustForwardHeader = false;

    /** 全局限流配置（null 表示关闭全局限流）。 */
    private Bucket global;

    /** 接口限流配置（null 表示关闭接口限流）。 */
    private Bucket api;

    /** 用户限流配置（null 表示关闭用户限流）。 */
    private Bucket user;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getIpWhitelist() {
        return ipWhitelist;
    }

    public void setIpWhitelist(List<String> ipWhitelist) {
        this.ipWhitelist = ipWhitelist == null ? List.of() : ipWhitelist;
    }

    public boolean isTrustForwardHeader() {
        return trustForwardHeader;
    }

    public void setTrustForwardHeader(boolean trustForwardHeader) {
        this.trustForwardHeader = trustForwardHeader;
    }

    public Bucket getGlobal() {
        return global;
    }

    public void setGlobal(Bucket global) {
        this.global = global;
    }

    public Bucket getApi() {
        return api;
    }

    public void setApi(Bucket api) {
        this.api = api;
    }

    public Bucket getUser() {
        return user;
    }

    public void setUser(Bucket user) {
        this.user = user;
    }

    /**
     * 令牌桶参数。
     */
    public static class Bucket {

        /** 桶容量（突发请求数）。 */
        private int capacity = 10;

        /** 每个补充周期放入的令牌数。 */
        private int refillTokens = 10;

        /** 补充周期（秒）。 */
        private int refillSeconds = 1;

        /** Redis key 空闲过期时间（秒）。 */
        private long expireSeconds = 120;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillTokens() {
            return refillTokens;
        }

        public void setRefillTokens(int refillTokens) {
            this.refillTokens = refillTokens;
        }

        public int getRefillSeconds() {
            return refillSeconds;
        }

        public void setRefillSeconds(int refillSeconds) {
            this.refillSeconds = refillSeconds;
        }

        public long getExpireSeconds() {
            return expireSeconds;
        }

        public void setExpireSeconds(long expireSeconds) {
            this.expireSeconds = expireSeconds;
        }

        /** 每秒补充速率，仅用于日志/响应头展示。 */
        public double refillRatePerSecond() {
            return refillSeconds <= 0 ? refillTokens : (double) refillTokens / refillSeconds;
        }
    }
}
