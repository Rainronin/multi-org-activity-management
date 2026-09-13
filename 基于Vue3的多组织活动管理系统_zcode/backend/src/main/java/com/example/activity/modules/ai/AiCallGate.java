package com.example.activity.modules.ai;

import com.example.activity.common.ApiException;
import com.example.activity.security.AuthRateLimiter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 调用门控（后端架构 6.4／6.3）：部署开关、组织频率、当日额度与单实例并发。
 *
 * <p>额度按持久生成记录计算（含连接测试），跨重启仍有效；并发门控只限制正在进行的调用，
 * 不为限流搭建完整分布式平台。未取得许可时抛错且不持有任何许可。
 */
@Component
public class AiCallGate {

    private final AiProperties properties;
    private final AiGenerationMapper generationMapper;
    private final AuthRateLimiter rateLimiter;
    private final Semaphore semaphore;

    public AiCallGate(
        AiProperties properties, AiGenerationMapper generationMapper, AuthRateLimiter rateLimiter) {
        this.properties = properties;
        this.generationMapper = generationMapper;
        this.rateLimiter = rateLimiter;
        this.semaphore = new Semaphore(Math.max(1, properties.getMaxConcurrentCalls()));
    }

    /** 取得一次调用许可；正常返回表示持有许可，调用方必须在 finally 中 {@link #release()}。 */
    public void acquire(long orgId) {
        if (!properties.isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED", "AI 功能已关闭",
                "部署环境关闭了模型调用；手工编写方案与总结不受影响。");
        }
        AuthRateLimiter.Decision decision =
            rateLimiter.tryAcquire("ai:" + orgId, properties.getMaxCallsPerFiveMinutesPerOrg());
        if (!decision.allowed()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMITED", "调用过于频繁",
                "同一组织 5 分钟内最多调用 " + properties.getMaxCallsPerFiveMinutesPerOrg()
                    + " 次，请在 " + decision.retryAfterSeconds() + " 秒后重试；手工编写不受影响。");
        }
        long used = generationMapper.countSince(orgId, startOfUtcDay());
        if (used >= properties.getMaxCallsPerDayPerOrg()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_QUOTA_EXCEEDED", "当日调用额度已用完",
                "本组织当日模型调用已达 " + properties.getMaxCallsPerDayPerOrg()
                    + " 次（含连接测试），次日恢复；手工编写不受影响。");
        }
        if (!semaphore.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUSY", "模型调用繁忙",
                "当前并发的模型调用已达上限 " + properties.getMaxConcurrentCalls() + "，请稍后重试。");
        }
    }

    public void release() {
        semaphore.release();
    }

    private OffsetDateTime startOfUtcDay() {
        return OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
