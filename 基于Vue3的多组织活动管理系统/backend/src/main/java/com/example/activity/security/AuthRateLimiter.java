package com.example.activity.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 首期进程内限流：固定窗口计数，覆盖登录与恢复入口（注册、恢复、重签）。
 * 单实例部署下够用；引入多实例前不部署 Redis 或网关限流。
 */
@Component
public class AuthRateLimiter {

    /** 窗口 5 分钟；登录按账号+IP，恢复与注册按来源 IP，重签按账号共用同一实现。 */
    private static final long WINDOW_MILLIS = 5 * 60 * 1000L;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    /** 只检查不计数：登录在失败处理器中才记录，成功后清除。 */
    public Decision checkOnly(String key, int maxAttempts) {
        Window window = windows.get(key);
        if (window == null) {
            return new Decision(true, 0);
        }
        long now = System.currentTimeMillis();
        if (now - window.windowStart >= WINDOW_MILLIS) {
            return new Decision(true, 0);
        }
        if (window.count >= maxAttempts) {
            return new Decision(false, Math.max(1, (WINDOW_MILLIS - (now - window.windowStart)) / 1000));
        }
        return new Decision(true, 0);
    }

    public Decision tryAcquire(String key, int maxAttempts) {
        long now = System.currentTimeMillis();
        Window window = windows.compute(key, (k, existing) ->
            existing == null || now - existing.windowStart >= WINDOW_MILLIS ? new Window(now) : existing);
        synchronized (window) {
            if (window.count >= maxAttempts) {
                long elapsed = now - window.windowStart;
                return new Decision(false, Math.max(1, (WINDOW_MILLIS - elapsed) / 1000));
            }
            window.count++;
            return new Decision(true, 0);
        }
    }

    /** 登录成功后清除对应计数，避免正常用户被历史失败拖入限流。 */
    public void reset(String key) {
        windows.remove(key);
    }

    private static final class Window {
        private final long windowStart;
        private int count;

        private Window(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
