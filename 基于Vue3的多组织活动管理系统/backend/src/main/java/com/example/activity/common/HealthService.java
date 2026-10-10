package com.example.activity.common;

import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private final HealthMapper healthMapper;

    public HealthService(HealthMapper healthMapper) {
        this.healthMapper = healthMapper;
    }

    public boolean isDatabaseReachable() {
        return healthMapper.selectOne() == 1;
    }
}
