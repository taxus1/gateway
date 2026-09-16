package com.example.demob.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 服务 B 的连通性验证接口。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${spring.application.name}")
    private String applicationName;

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "service", applicationName,
                "message", "pong from service-b",
                "timestamp", Instant.now().toString());
    }
}
