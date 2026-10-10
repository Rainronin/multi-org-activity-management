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
 * 各测试类共用的 MockMvc／JdbcTemplate 及 F05 版本前置条件辅助方法也集中在此。
 */
public abstract class ActivityTestBase {

    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    @org.springframework.beans.factory.annotation.Autowired
    protected org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** S08：创建活动的 Idempotency-Key；每次调用生成新键，防重测试另行复用同一键。 */
    protected static String newIdempotencyKey() {
        return "k" + System.nanoTime() + KEY_SEQ.incrementAndGet();
    }

    /** F05：活动当前版本；编辑、方案保存与审批请求必须携带期望版本。 */
    protected Long activityVersion(Long activityId) {
        return jdbcTemplate.queryForObject("SELECT version FROM activity WHERE id = ?", Long.class, activityId);
    }

    /** F05：把活动当前版本补进请求体（空对象与带字段的请求体都支持）。 */
    protected String versioned(Long activityId, String body) {
        return withVersion(activityVersion(activityId), body);
    }

    /** 同上，供以字符串保存活动 id 的用例使用。 */
    protected String versioned(String activityId, String body) {
        return versioned(Long.parseLong(activityId), body);
    }

    /** F05：任务改派／编辑的期望版本来自任务自身，不能复用活动版本。 */
    protected String taskVersioned(Long taskId, String body) {
        return withVersion(
            jdbcTemplate.queryForObject("SELECT version FROM task WHERE id = ?", Long.class, taskId), body);
    }

    /** 同上，供以字符串保存任务 id 的用例使用。 */
    protected String taskVersioned(String taskId, String body) {
        return taskVersioned(Long.parseLong(taskId), body);
    }

    /** 报名审批／取消绑定当前报名版本，重新申请不能沿用上一轮审批。 */
    protected String registrationVersioned(Long registrationId, String body) {
        return withVersion(jdbcTemplate.queryForObject(
            "SELECT version FROM registration WHERE id = ?", Long.class, registrationId), body);
    }

    protected String registrationVersioned(String registrationId, String body) {
        return registrationVersioned(Long.parseLong(registrationId), body);
    }

    protected String registrationForAccountVersioned(Long activityId, Long accountId, String body) {
        var versions = jdbcTemplate.queryForList("SELECT version FROM registration WHERE activity_id = ? AND account_id = ?",
            Long.class, activityId, accountId);
        return withVersion(versions.isEmpty() ? 0L : versions.getFirst(), body);
    }

    protected String registrationForAccountVersioned(Long activityId, String username, String body) {
        return registrationForAccountVersioned(activityId, jdbcTemplate.queryForObject(
            "SELECT id FROM account WHERE username = ?", Long.class, username), body);
    }

    protected String feedbackVersioned(Long activityId, Long accountId, String body) {
        var versions = jdbcTemplate.queryForList("SELECT version FROM feedback WHERE activity_id = ? AND account_id = ?",
            Long.class, activityId, accountId);
        return withVersion(versions.isEmpty() ? 0L : versions.getFirst(), body);
    }

    protected String feedbackVersioned(Long activityId, String username, String body) {
        return feedbackVersioned(activityId, jdbcTemplate.queryForObject(
            "SELECT id FROM account WHERE username = ?", Long.class, username), body);
    }

    private String withVersion(long version, String body) {
        String trimmed = body.trim();
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        return "{" + (inner.isEmpty() ? "" : inner + ",") + "\"version\":" + version + "}";
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
        int port = Integer.parseInt(System.getProperty("activity.test-db-port", "3306"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("测试数据库端口必须在 1—65535 之间");
        }
        registry.add("spring.datasource.url",
            () -> "jdbc:mysql://127.0.0.1:" + port
                + "/activity_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("app.upload.dir", () -> UPLOAD_DIR.toString());
    }
}
