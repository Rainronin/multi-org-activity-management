-- P4：活动报名主记录（同活动同账号唯一）。
CREATE TABLE registration (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    activity_id BIGINT UNSIGNED NOT NULL,
    account_id BIGINT UNSIGNED NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    note VARCHAR(500) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    decided_by BIGINT UNSIGNED NULL,
    decided_at TIMESTAMP(6) NULL,
    cancel_reason VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_registration_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_registration_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT fk_registration_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_registration_decider FOREIGN KEY (decided_by) REFERENCES account (id),
    UNIQUE KEY uk_registration_activity_account (activity_id, account_id),
    KEY idx_registration_status (org_id, activity_id, status),
    KEY idx_registration_account (account_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
