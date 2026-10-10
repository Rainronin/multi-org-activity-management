-- 活动模块（M02，P2.1）：活动主表与草稿字段。方案文稿（P2.2）与状态机扩展随对应阶段增量迁移。
-- 金额 DECIMAL(12,2) 人民币；时间点 UTC；CHECK 约束兜底预算非负与名额正整数。

CREATE TABLE activity (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(2000) NULL,
    owner_member_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    start_time TIMESTAMP(6) NULL,
    end_time TIMESTAMP(6) NULL,
    location VARCHAR(255) NULL,
    budget DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    registration_scope VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
    capacity INT UNSIGNED NULL,
    registration_start TIMESTAMP(6) NULL,
    registration_end TIMESTAMP(6) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_activity_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_activity_owner FOREIGN KEY (owner_member_id) REFERENCES organization_member (id),
    CONSTRAINT fk_activity_creator FOREIGN KEY (created_by) REFERENCES account (id),
    CONSTRAINT ck_activity_budget CHECK (budget >= 0),
    CONSTRAINT ck_activity_capacity CHECK (capacity IS NULL OR capacity > 0),
    KEY idx_activity_org_status (org_id, status),
    KEY idx_activity_owner (owner_member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
