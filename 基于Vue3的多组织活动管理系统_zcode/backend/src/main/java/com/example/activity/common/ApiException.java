package com.example.activity.common;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态、业务错误码（UPPER_SNAKE_CASE）、标题与可选字段错误，
 * 由 GlobalExceptionHandler 统一映射为 ProblemDetail，不在各层自行序列化错误。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final List<FieldErrorItem> fieldErrors;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, List.of());
    }

    public ApiException(HttpStatus status, String code, String title, String detail, List<FieldErrorItem> fieldErrors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.fieldErrors = List.copyOf(fieldErrors);
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

    public List<FieldErrorItem> getFieldErrors() {
        return fieldErrors;
    }

    public String getDetail() {
        return getMessage();
    }
}
