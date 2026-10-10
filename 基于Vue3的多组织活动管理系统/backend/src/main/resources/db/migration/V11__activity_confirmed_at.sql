-- S07：关键变更与删除判定的持久依据。
-- confirmed_at 记录最近一次立项确认时间；撤回后为 DRAFT 且 confirmed_at 非空表示“调整期待确认”，
-- 用于区分初始草稿与曾确认后撤回的活动（后者不得按初始草稿删除，任务与物资在期间只读）。
ALTER TABLE activity
    ADD COLUMN confirmed_at TIMESTAMP(6) NULL AFTER confirm_type;

-- 回填历史已确认活动：取审计日志中最近一次立项通过的时间。
UPDATE activity a
    JOIN (
        SELECT target, MAX(created_at) AS confirmed_at
        FROM audit_log
        WHERE action = 'ACTIVITY_APPROVED'
        GROUP BY target
    ) log ON log.target = CONCAT('activity:', a.id)
SET a.confirmed_at = log.confirmed_at
WHERE a.confirmed_at IS NULL;
