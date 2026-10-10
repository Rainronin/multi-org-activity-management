-- 组织创建的可选防重键；历史记录及旧无键调用继续兼容。
ALTER TABLE organization
    ADD COLUMN created_by BIGINT UNSIGNED NULL AFTER status,
    ADD COLUMN request_key VARCHAR(64) NULL AFTER created_by,
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER request_key,
    ADD UNIQUE KEY uk_organization_creator_request_key (created_by, request_key),
    ADD CONSTRAINT fk_organization_creator FOREIGN KEY (created_by) REFERENCES account (id);
