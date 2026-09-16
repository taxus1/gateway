package com.example.gateway.config;

import com.github.fppt.jedismock.RedisServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;

/**
 * 持有内嵌 Redis 实例，供 {@link EmbeddedRedisEnvironmentPostProcessor} 启动、
 * {@link EmbeddedRedisConfig} 注册生命周期回调。
 */
public final class EmbeddedRedisHolder {

    private static volatile RedisServer server;

    private EmbeddedRedisHolder() {
    }

    public static synchronized RedisServer start(int port) {
        if (server != null && server.isRunning()) {
            return server;
        }
        try {
            RedisServer redisServer = port > 0
                    ? RedisServer.newRedisServer(port, InetAddress.getLoopbackAddress())
                    : RedisServer.newRedisServer(0, InetAddress.getLoopbackAddress());
            redisServer.start();
            server = redisServer;
            return redisServer;
        } catch (IOException e) {
            throw new UncheckedIOException("内嵌 Redis 启动失败", e);
        }
    }

    public static RedisServer getServer() {
        return server;
    }

    public static synchronized void stop() {
        if (server != null && server.isRunning()) {
            try {
                server.stop();
            } catch (IOException e) {
                throw new UncheckedIOException("内嵌 Redis 关闭失败", e);
            } finally {
                server = null;
            }
        }
    }
}
