package com.example.activity.common;

import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读健康检查：证明前后端到数据库的链路可用，不含业务数据，不属于业务模块。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    public record HealthResponse(String status, String database, OffsetDateTime checkedAt) {
    }

    @GetMapping
    public HealthResponse health() {
        boolean databaseUp = healthService.isDatabaseReachable();
        return new HealthResponse("UP", databaseUp ? "UP" : "DOWN", OffsetDateTime.now());
    }
}
