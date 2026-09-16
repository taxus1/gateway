package com.example.gateway.exception;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param timestamp 错误发生时间（UTC）
 * @param status    HTTP 状态码
 * @param error     状态码描述
 * @param code      业务错误码
 * @param message   错误信息
 * @param path      请求路径
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path) {

    public static ErrorResponse of(int status, String error, String code, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, code, message, path);
    }
}
