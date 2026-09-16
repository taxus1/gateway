package com.example.gateway.exception;

import com.example.gateway.ratelimit.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.util.DisconnectedClientHelper;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关统一全局异常处理。
 *
 * <p>以最高优先级（早于 Spring Cloud Gateway / WebFlux 默认处理器）拦截所有异常，
 * 统一输出 {@link ErrorResponse} 结构的 JSON：
 * <ul>
 *     <li>限流异常 → 429 TOO_MANY_REQUESTS；</li>
 *     <li>响应状态异常（如 404 路由不存在）→ 对应状态码；</li>
 *     <li>参数 / 请求体异常 → 400；</li>
 *     <li>方法不支持 → 405；</li>
 *     <li>其余未预期异常 → 500。</li>
 * </ul>
 */
@Order(-2)
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    /** 比 WebFlux 默认的 DefaultErrorWebExceptionHandler(-1) 更高优先级。 */
    public static final int HANDLER_ORDER = -2;

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        // 客户端断连等无需再写响应的场景
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            return Mono.empty();
        }
        if (response.isCommitted()) {
            log.warn("响应已提交，无法处理异常: {}", ex.toString());
            return Mono.error(ex);
        }

        HttpStatus status;
        String code;
        String message;

        if (ex instanceof RateLimitExceededException rateLimitEx) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            code = "RATE_LIMITED";
            message = "请求过于频繁，请稍后再试（维度: " + rateLimitEx.getDimension() + "）";
            long retryAfter = rateLimitEx.getResult().retryAfterSeconds();
            if (retryAfter > 0) {
                response.getHeaders().set("Retry-After", String.valueOf(retryAfter));
            }
            log.debug("限流拒绝: {}", rateLimitEx.getMessage());
        } else if (ex instanceof ServerWebInputException) {
            status = HttpStatus.BAD_REQUEST;
            code = "BAD_REQUEST";
            message = "请求参数不合法: " + ex.getMessage();
        } else if (ex instanceof HttpRequestMethodNotSupportedException) {
            status = HttpStatus.METHOD_NOT_ALLOWED;
            code = "METHOD_NOT_ALLOWED";
            message = ex.getMessage();
        } else if (ex instanceof ResponseStatusException rse) {
            HttpStatus resolved = HttpStatus.resolve(rse.getStatusCode().value());
            status = resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
            code = status.name();
            message = rse.getReason() != null ? rse.getReason() : status.getReasonPhrase();
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = "INTERNAL_SERVER_ERROR";
            message = "网关内部错误";
            log.error("网关未处理异常", ex);
        }

        ErrorResponse body = ErrorResponse.of(
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                exchange.getRequest().getPath().value());

        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));

        return response.writeWith(Mono.fromSupplier(() -> {
                    byte[] json = JsonWriter.write(body);
                    return response.bufferFactory().wrap(json);
                }))
                .onErrorResume(writeError -> {
                    log.warn("错误响应写出失败: {}", writeError.toString());
                    return Mono.empty();
                });
    }
}
