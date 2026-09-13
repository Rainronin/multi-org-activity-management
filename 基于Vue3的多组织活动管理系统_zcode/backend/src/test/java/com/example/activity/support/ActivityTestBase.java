package com.example.activity.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 测试库守护（R08）：强制测试上下文连接 activity_test，凭据仍由本地配置或环境变量注入。
 * 任何写测试或 Flyway 迁移都不可能落在开发库上；DatabaseSmokeTest 另行断言实际 URL。
 * 上传目录同时指向临时目录，测试产生的附件不写入源码树。
 * 同时提供 S08 创建防重所需的 Idempotency-Key 生成入口，供各测试类统一使用。
 */
public abstract class ActivityTestBase {

    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    /** S08：创建活动的 Idempotency-Key；每次调用生成新键，防重测试另行复用同一键。 */
    protected static String newIdempotencyKey() {
        return "k" + System.nanoTime() + KEY_SEQ.incrementAndGet();
    }

    private static final Path UPLOAD_DIR = createTempUploadDir();

    private static Path createTempUploadDir() {
        try {
            Path dir = Files.createTempDirectory("activity-test-uploads-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(dir)));
            return dir;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录残留由系统清理，不影响测试结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    @DynamicPropertySource
    static void forceTestDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
            () -> "jdbc:mysql://127.0.0.1:3306/activity_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("app.upload.dir", () -> UPLOAD_DIR.toString());
    }
}
