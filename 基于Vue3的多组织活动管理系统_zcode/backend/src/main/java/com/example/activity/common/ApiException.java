package com.example.activity.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态、业务错误码（UPPER_SNAKE_CASE）与标题，
 * 由 GlobalExceptionHandler 统一映射为 ProblemDetail，不在各层自行序列化错误。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return getMessage();
    }
}
