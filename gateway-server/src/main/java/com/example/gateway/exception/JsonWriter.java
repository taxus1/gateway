package com.example.gateway.exception;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 极简 JSON 序列化工具（错误响应场景下不依赖容器中 ObjectMapper 的定制）。
 * Boot 4 内置 Jackson 3（包名 tools.jackson.*），Java 8 时间类型默认支持。
 */
final class JsonWriter {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private JsonWriter() {
    }

    static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            return ("{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"错误响应序列化失败\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
