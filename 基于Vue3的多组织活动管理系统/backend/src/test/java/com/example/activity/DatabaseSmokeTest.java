package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.activity.support.ActivityTestBase;
import org.springframework.test.context.ActiveProfiles;

import com.example.activity.common.HealthService;

/**
 * 真实 MySQL 测试库冒烟检查：连接 activity_test、业务查询可用、Flyway 迁移已应用。
 * 数据库锁与约束类测试遵循同一原则——使用隔离的真实 MySQL，不用 mock 或 H2 推断。
 */
@SpringBootTest
@ActiveProfiles("local")
class DatabaseSmokeTest extends ActivityTestBase {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HealthService healthService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void databaseIsReachableAndMigrationsApplied() throws SQLException {
        assertThat(healthService.isDatabaseReachable()).isTrue();

        String url;
        try (Connection connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        }
        assertThat(url).contains("activity_test");

        Integer applied = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }
}
