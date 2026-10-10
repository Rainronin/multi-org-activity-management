-- S10：任务附件用途。附件可关联活动资料或任务资料，二选一（后端架构 6.1：只能关联一个合法业务对象）。
ALTER TABLE attachment
    ADD COLUMN task_id BIGINT UNSIGNED NULL AFTER activity_id,
    ADD CONSTRAINT fk_attachment_task FOREIGN KEY (task_id) REFERENCES task (id),
    ADD KEY idx_attachment_task (task_id, deleted_at);
