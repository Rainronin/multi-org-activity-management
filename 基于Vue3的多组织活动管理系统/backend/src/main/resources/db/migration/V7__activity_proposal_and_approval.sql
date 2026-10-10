-- P2.2 人工方案与单级确认：方案文稿存于活动行（activity 拥有方案）；审批字段与提交者／确认者留痕。
-- 处理历史（提交、撤回、通过、驳回及原因）以 audit_log 记录（reason 字段）。

ALTER TABLE activity
    ADD COLUMN proposal LONGTEXT NULL AFTER location,
    ADD COLUMN review_note VARCHAR(500) NULL AFTER status,
    ADD COLUMN submitted_by BIGINT UNSIGNED NULL AFTER review_note,
    ADD COLUMN confirmed_by BIGINT UNSIGNED NULL AFTER submitted_by,
    ADD COLUMN confirm_type VARCHAR(16) NULL AFTER confirmed_by,
    ADD CONSTRAINT fk_activity_submitter FOREIGN KEY (submitted_by) REFERENCES account (id),
    ADD CONSTRAINT fk_activity_confirmer FOREIGN KEY (confirmed_by) REFERENCES account (id);
