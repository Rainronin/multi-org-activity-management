-- P5：活动经费（M05）。支出登记与确认链路：草稿→待确认→已确认／已驳回；已确认后只能作废或另建更正记录。
-- 金额精确保存（DECIMAL(12,2)），必须大于零；无票必须写说明；超支由组织负责人填写说明后仍可确认。
-- 附件新增费用凭证用途：附件只能关联活动、任务或费用之一（后端架构 6.1）。

CREATE TABLE expense (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    activity_id BIGINT UNSIGNED NOT NULL,
    purpose VARCHAR(200) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    incurred_on DATE NOT NULL,
    category VARCHAR(32) NOT NULL,
    handled_note VARCHAR(500) NULL,
    no_receipt_note VARCHAR(500) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    submitted_by BIGINT UNSIGNED NULL,
    submitted_at TIMESTAMP(6) NULL,
    decided_by BIGINT UNSIGNED NULL,
    decided_at TIMESTAMP(6) NULL,
    reject_reason VARCHAR(500) NULL,
    over_budget_note VARCHAR(500) NULL,
    void_reason VARCHAR(500) NULL,
    voided_at TIMESTAMP(6) NULL,
    corrects_expense_id BIGINT UNSIGNED NULL,
    version BIGINT NOT NULL DEFAULT 1,
    request_key VARCHAR(64) NULL,
    request_fingerprint CHAR(64) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_expense_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_expense_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT fk_expense_corrects FOREIGN KEY (corrects_expense_id) REFERENCES expense (id),
    CONSTRAINT ck_expense_amount_positive CHECK (amount > 0),
    UNIQUE KEY uk_expense_org_request_key (org_id, request_key),
    KEY idx_expense_activity_status (activity_id, status),
    KEY idx_expense_corrects (corrects_expense_id)
);

ALTER TABLE attachment
    ADD COLUMN expense_id BIGINT UNSIGNED NULL AFTER task_id,
    ADD CONSTRAINT fk_attachment_expense FOREIGN KEY (expense_id) REFERENCES expense (id),
    ADD KEY idx_attachment_expense (expense_id, deleted_at);
