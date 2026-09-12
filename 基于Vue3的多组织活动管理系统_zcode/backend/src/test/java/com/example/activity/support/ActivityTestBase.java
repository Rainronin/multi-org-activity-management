package com.example.activity.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 测试库守护（R08）：强制测试上下文连接 activity_test，凭据仍由本地配置或环境变量注入。
 * 任何写测试或 Flyway 迁移都不可能落在开发库上；DatabaseSmokeTest 另行断言实际 URL。
 */
public abstract class ActivityTestBase {

    @DynamicPropertySource
    static void forceTestDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
            () -> "jdbc:mysql://127.0.0.1:3306/activity_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    }
}
