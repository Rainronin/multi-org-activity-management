package com.example.activity.common;

import org.springframework.http.HttpStatus;

/** 限流异常：统一 429 结构并携带 Retry-After 秒数。 */
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁", "操作过于频繁，请稍后重试。");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
