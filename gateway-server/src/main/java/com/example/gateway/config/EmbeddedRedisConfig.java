package com.example.gateway.config;

import com.github.fppt.jedismock.RedisServer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 内嵌 Redis 生命周期管理。
 *
 * <p>Redis 实例由 {@link EmbeddedRedisEnvironmentPostProcessor} 在 Spring 环境准备阶段启动，
 * 这里仅在应用关闭时负责停止它。使用 {@code external} profile 连接外部 Redis 时不生效。
 */
@Configuration
@Profile("!external")
public class EmbeddedRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedRedisConfig.class);

    @PreDestroy
    public void stopRedis() {
        RedisServer server = EmbeddedRedisHolder.getServer();
        if (server != null && server.isRunning()) {
            EmbeddedRedisHolder.stop();
            log.info("内嵌 Redis 已关闭");
        }
    }
}
