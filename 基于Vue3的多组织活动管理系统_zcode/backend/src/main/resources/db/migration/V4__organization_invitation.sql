-- 组织邀请（M01，P1.3）：邀请码仅存哈希；有效期与使用次数在锁内校验。

CREATE TABLE organization_invitation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    code_hash CHAR(64) NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    max_uses INT UNSIGNED NOT NULL,
    used_count INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_invitation_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_invitation_creator FOREIGN KEY (created_by) REFERENCES account (id),
    KEY idx_invitation_org_status (org_id, status),
    KEY idx_invitation_code_hash (code_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
