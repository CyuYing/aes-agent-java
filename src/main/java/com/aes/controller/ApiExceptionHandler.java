package com.aes.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 为页面和 API 调用方提供稳定、可读且不泄露内部堆栈的错误结构。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException error) {
        return response(error.getStatusCode().value(),
                error.getReason() == null ? "请求处理失败" : error.getReason());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException error) {
        return response(HttpStatus.BAD_REQUEST.value(), safeMessage(error, "请求参数不正确"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleServiceFailure(IllegalStateException error) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                safeMessage(error, "服务暂时不可用"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(
            MaxUploadSizeExceededException error) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "上传内容过大：单个文件不得超过 10MB，整次请求不得超过 50MB");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception error) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务器处理失败，请查看服务日志");
    }

    private ResponseEntity<Map<String, Object>> response(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private String safeMessage(Exception error, String fallback) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return fallback;
        return message
                .replaceAll("sk-[A-Za-z0-9._-]{8,}", "sk-[已隐藏]")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._-]+", "Bearer [已隐藏]")
                .replaceAll("(?i)(api[_ -]?key|authorization)[^,;\\n]*", "$1=[已隐藏]");
    }
}
