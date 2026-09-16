package com.example.gateway.config;

import com.github.fppt.jedismock.RedisServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.HashMap;
import java.util.Map;

/**
 * 在 Spring 上下文刷新之前启动内嵌 Redis，并把实际监听地址写入环境，
 * 保证 Lettuce 连接工厂（依据 spring.data.redis.* 构建）拿到正确端口。
 *
 * <p>使用 {@code --spring.profiles.active=external} 启动时跳过，改用 application.yml
 * 中配置的外部 Redis。
 */
public class EmbeddedRedisEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedRedisEnvironmentPostProcessor.class);

    private static final String PROPERTY_SOURCE_NAME = "embeddedRedis";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.matchesProfiles("external")) {
            log.info("检测到 external profile，跳过内嵌 Redis，使用外部 Redis 配置");
            return;
        }

        int port = environment.getProperty("app.embedded-redis.port", Integer.class, 0);
        RedisServer server = EmbeddedRedisHolder.start(port);

        Map<String, Object> redisProperties = new HashMap<>();
        redisProperties.put("spring.data.redis.host", server.getHost());
        redisProperties.put("spring.data.redis.port", server.getBindPort());

        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(PROPERTY_SOURCE_NAME)) {
            sources.replace(PROPERTY_SOURCE_NAME, new MapPropertySource(PROPERTY_SOURCE_NAME, redisProperties));
        } else {
            sources.addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, redisProperties));
        }
        log.info("内嵌 Redis 已启动: {}:{}", server.getHost(), server.getBindPort());
    }
}
