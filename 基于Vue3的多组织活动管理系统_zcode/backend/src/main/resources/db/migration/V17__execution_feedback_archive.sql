-- P6：活动执行、取消、反馈、总结与归档。
-- 1) activity 扩展主状态相关字段：执行开始／结束、归档、取消、重新打开留痕与总结确认。
-- 2) execution_record 执行记录（进展、问题、临时安排、实际差异、处理结果）只追加，不覆盖事前审批内容。
-- 3) feedback_eligibility 反馈资格依据：执行开始时固定已批准报名者，执行结束时固定负责人与任务承担者。
-- 4) feedback 反馈：每账号每活动一份，1～5 分与文字建议；归档后关闭修改，取消活动保留历史不新增。

ALTER TABLE activity
    ADD COLUMN started_at TIMESTAMP(6) NULL AFTER confirmed_at,
    ADD COLUMN ended_at TIMESTAMP(6) NULL AFTER started_at,
    ADD COLUMN archived_at TIMESTAMP(6) NULL AFTER ended_at,
    ADD COLUMN cancelled_at TIMESTAMP(6) NULL AFTER archived_at,
    ADD COLUMN cancel_reason VARCHAR(500) NULL AFTER cancelled_at,
    ADD COLUMN reopen_reason VARCHAR(500) NULL AFTER cancel_reason,
    ADD COLUMN reopen_count INT NOT NULL DEFAULT 0 AFTER reopen_reason,
    ADD COLUMN execution_snapshot_hash CHAR(64) NULL AFTER reopen_count,
    ADD COLUMN summary TEXT NULL AFTER execution_snapshot_hash,
    ADD COLUMN summary_confirmed_at TIMESTAMP(6) NULL AFTER summary,
    ADD COLUMN summary_confirmed_by BIGINT UNSIGNED NULL AFTER summary_confirmed_at,
    ADD COLUMN summary_source_hash CHAR(64) NULL AFTER summary_confirmed_by;

CREATE TABLE execution_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    activity_id BIGINT UNSIGNED NOT NULL,
    category VARCHAR(24) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    recorded_by BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_execution_record_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_execution_record_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    KEY idx_execution_record_activity (activity_id, created_at)
);

CREATE TABLE feedback_eligibility (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_id BIGINT UNSIGNED NOT NULL,
    account_id BIGINT UNSIGNED NOT NULL,
    source VARCHAR(24) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_feedback_eligibility_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    UNIQUE KEY uk_feedback_eligibility (activity_id, account_id, source),
    KEY idx_feedback_eligibility_account (activity_id, account_id)
);

CREATE TABLE feedback (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    activity_id BIGINT UNSIGNED NOT NULL,
    account_id BIGINT UNSIGNED NOT NULL,
    rating TINYINT NOT NULL,
    comment VARCHAR(2000) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_feedback_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_feedback_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT ck_feedback_rating CHECK (rating BETWEEN 1 AND 5),
    UNIQUE KEY uk_feedback_activity_account (activity_id, account_id),
    KEY idx_feedback_activity (activity_id)
);
