-- P3：受控附件、活动任务、物资准备与站内通知意图。
-- 附件存储于服务端受控目录（随机存储名），元数据含软删除标记；任务与物资状态机独立；
-- 通知仅存最小意图（接收人、类型、摘要与资源引用），P7 建立展示与补建。

CREATE TABLE attachment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    activity_id BIGINT UNSIGNED NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    stored_name VARCHAR(64) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    uploaded_by BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_attachment_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_attachment_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploaded_by) REFERENCES account (id),
    UNIQUE KEY uk_attachment_stored_name (stored_name),
    KEY idx_attachment_activity (activity_id, deleted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    activity_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(128) NOT NULL,
    description VARCHAR(2000) NULL,
    assignee_member_id BIGINT UNSIGNED NOT NULL,
    priority VARCHAR(8) NOT NULL DEFAULT 'MEDIUM',
    due_at TIMESTAMP(6) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    completed_at TIMESTAMP(6) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_task_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_task_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT fk_task_assignee FOREIGN KEY (assignee_member_id) REFERENCES organization_member (id),
    CONSTRAINT fk_task_creator FOREIGN KEY (created_by) REFERENCES account (id),
    KEY idx_task_activity_status (activity_id, status),
    KEY idx_task_assignee (assignee_member_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE material (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    activity_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(128) NOT NULL,
    quantity INT UNSIGNED NOT NULL,
    unit VARCHAR(16) NOT NULL,
    assignee_member_id BIGINT UNSIGNED NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    note VARCHAR(500) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_material_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_material_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT fk_material_assignee FOREIGN KEY (assignee_member_id) REFERENCES organization_member (id),
    CONSTRAINT fk_material_creator FOREIGN KEY (created_by) REFERENCES account (id),
    KEY idx_material_activity_status (activity_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE notification (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    recipient_account_id BIGINT UNSIGNED NOT NULL,
    type VARCHAR(32) NOT NULL,
    activity_id BIGINT UNSIGNED NULL,
    payload VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    read_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_account_id) REFERENCES account (id),
    KEY idx_notification_recipient (recipient_account_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
