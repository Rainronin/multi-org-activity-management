package com.example.activity.common;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一异常映射：Spring 原生 ProblemDetail（application/problem+json）加业务字段
 * code、requestId、fieldErrors（开发规范 2.3）。未预期异常只在边界记录一次并返回 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(ex.getStatus());
        problem.setTitle(ex.getTitle());
        problem.setDetail(ex.getDetail());
        problem.setProperty("code", ex.getCode());
        if (!ex.getFieldErrors().isEmpty()) {
            problem.setProperty("fieldErrors", ex.getFieldErrors());
        }
        return enrich(problem);
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitedException ex) {
        return ResponseEntity.status(ex.getStatus())
            .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
            .body(handleApiException(ex));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("资源不存在");
        problem.setDetail("请求的资源不存在或无权访问。");
        problem.setProperty("code", "RESOURCE_NOT_FOUND");
        return enrich(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBodyValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("输入无效");
        problem.setDetail("请求字段校验失败，请修改后重试。");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("fieldErrors", ex.getBindingResult().getFieldErrors().stream()
            .map(fieldError -> new FieldErrorItem(fieldError.getField(), fieldError.getDefaultMessage()))
            .toList());
        return enrich(problem);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleParameterValidation(HandlerMethodValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("输入无效");
        problem.setDetail("请求参数校验失败，请修改后重试。");
        problem.setProperty("code", "VALIDATION_FAILED");
        List<FieldErrorItem> fieldErrors = new ArrayList<>();
        ex.getAllValidationResults().forEach(result ->
            result.getResolvableErrors().forEach(error ->
                fieldErrors.add(new FieldErrorItem(result.getMethodParameter().getParameterName(), error.getDefaultMessage()))));
        problem.setProperty("fieldErrors", fieldErrors);
        return enrich(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("请求体无效");
        problem.setDetail("请求体不是合法的 JSON 或类型不匹配。");
        problem.setProperty("code", "MALFORMED_REQUEST_BODY");
        return enrich(problem);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problem.setTitle("不支持的内容类型");
        problem.setDetail("请求的 Content-Type 不被支持。");
        problem.setProperty("code", "UNSUPPORTED_MEDIA_TYPE");
        return enrich(problem);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.METHOD_NOT_ALLOWED);
        problem.setTitle("不支持该方法");
        problem.setDetail("该资源不支持此 HTTP 方法。");
        problem.setProperty("code", "METHOD_NOT_ALLOWED");
        return enrich(problem);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("服务内部错误");
        problem.setDetail("服务暂时不可用，请稍后重试。");
        problem.setProperty("code", "INTERNAL_ERROR");
        return enrich(problem);
    }

    private ProblemDetail enrich(ProblemDetail problem) {
        problem.setProperty("requestId", currentRequestId());
        return problem;
    }

    private String currentRequestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object requestId = attributes.getRequest().getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
            if (requestId instanceof String value) {
                return value;
            }
        }
        return "";
    }
}
