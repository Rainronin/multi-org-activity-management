-- S08：编辑冲突（version）与创建防重（request_key）。
-- 1) 活动、任务、物资增加整数 version：编辑与审批请求携带期望版本，服务端条件更新，
--    影响行数为 0 表示他人已保存，返回 409 而不是静默覆盖（开发规范第四节）。
-- 2) activity 增加 request_key：创建活动的 Idempotency-Key 按「组织 + 键」唯一，
--    同键重放返回已提交资源，同键异内容返回 409（开发规范第三节）。

ALTER TABLE activity
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER status,
    ADD COLUMN request_key VARCHAR(64) NULL AFTER created_by,
    ADD UNIQUE KEY uk_activity_org_request_key (org_id, request_key);

ALTER TABLE task
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER status;

ALTER TABLE material
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER status;
